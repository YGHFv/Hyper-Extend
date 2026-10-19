#!/usr/bin/env python3
"""离线执行真实原生诊断入口；系统调用、线程和 Binder 都由测试替身提供。"""

import argparse
import json
import struct
from pathlib import Path

from launcher_native_audit import AuditError, ElfImage, digest, require


BASE = 0x1000000
STACK = 0x2000000
STOP = 0x3000000
ENTRY = "launcher_diagnostic_trial"
CASES = {
    0: "合法快照",
    1: "拒绝 root",
    2: "拒绝 usap 池",
    3: "拒绝其他 spawner",
    4: "版本数字不影响入口可用性",
    5: "拒绝 UID 不一致",
    6: "拒绝隔离 UID",
    7: "拒绝空框架参数",
    8: "线程属性初始化失败不重试",
    9: "分离属性失败不重试",
    10: "线程创建失败不重试",
    11: "工作线程再次核验身份",
    12: "Binder 符号不可见",
    13: "window 服务不存在",
    14: "已有接口描述符不匹配",
    15: "关联类失败",
    16: "准备失败的输入 Parcel 释放",
    17: "事务失败的输出 Parcel 释放",
    18: "事务没有输出",
    19: "回复被截断",
    20: "未武装回复拒绝采纳",
    21: "重入查询不等待并返回忙",
    22: "fork 继承的排队工作拒绝执行",
    23: "fork 后不复用 Binder 和快照",
    24: "重复快照被拒绝",
    25: "修订号和功能位一起更新",
    26: "本进程特化后的加载回调可以调度",
    27: "继承回调需等待本进程重新初始化",
    28: "重复 native_init 不重新调度",
    **{30 + i: f"缺失第 {i + 1} 个 Binder 符号" for i in range(10)},
    40: "本地熔断锁存拒绝",
    41: "本次启动达到熔断阈值拒绝",
    42: "普通预检拒绝新恢复令牌且不读状态",
    43: "本地状态缺失不初始化",
    44: "本地状态格式错误拒绝",
    45: "本地状态读取失败拒绝",
    46: "本地状态提前到达 EOF 拒绝",
    47: "本地状态过长拒绝",
    48: "本地状态属主错误拒绝",
    49: "本地状态权限过宽拒绝",
    50: "本地状态不是普通文件拒绝",
    51: "本地状态符号链接打开失败不降级",
    52: "本地状态元信息读取失败拒绝",
    53: "本地状态关闭失败拒绝",
    54: "本地读取期间快照过期拒绝",
    55: "墙上时钟读取失败拒绝",
    56: "墙上时钟倒退拒绝",
    57: "墙上时钟纳秒越界拒绝",
    58: "墙上时钟毫秒溢出拒绝",
    59: "按多用户 UID 选择私有目录",
    60: "读取后发现额外字节拒绝",
    61: "分段读取完整状态仍需持久化",
    62: "私有目录属主错误拒绝",
    63: "私有目录可被其他身份写入拒绝",
    64: "files 目录缺失不创建",
    65: "用户目录不可访问不降级",
    66: "全关配置不读取本地状态",
    67: "回放快照不进入安全预检",
    68: "本地读取后身份失配拒绝",
    69: "本地状态有多个硬链接拒绝",
    70: "空状态文件拒绝",
    71: "普通预检拒绝饱和恢复令牌",
    72: "未熔断也拒绝非零恢复令牌",
    73: "相同非零令牌也不能混入普通预检",
    74: "零令牌保留本地恢复高水位",
    75: "零令牌不能清除带高水位的熔断",
    76: "Binder NDK 显式句柄解析符号",
}


def trial_entry(image, expected_entry=None):
    expected_entry = ENTRY if expected_entry is None else expected_entry
    definitions = [f for f in image.functions() if f["name"] == expected_entry]
    require(len(definitions) == 1, "试验入口缺失或重复")
    require(definitions[0]["size"] > 0, "试验入口为空")
    image.code(definitions[0]["address"], 4)
    for section in image.sections:
        require(not (section["name"] in (".init_array", ".preinit_array", ".ctors") and section["size"]),
                "试验不得包含加载期构造器")
        if section["type"] == 6:
            for off in range(section["offset"], section["offset"] + section["size"], 16):
                tag, _ = struct.unpack_from("<qQ", image.data, off)
                require(tag != 1, "试验不得依赖真实系统库")
    return definitions[0]["address"]


def run_case(image, entry, case, cases=None):
    import unicorn as uc
    from unicorn import arm64_const as reg

    machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
    segments = [s for s in image.segments if s["type"] == 1 and s["memory"]]
    require(segments and max(s["address"] + s["memory"] for s in segments) < 0x100000,
            "试验映像过大")
    machine.mem_map(BASE, 0x100000)
    for segment in segments:
        machine.mem_write(BASE + segment["address"], image.read(segment["offset"], segment["size"]))
    # 测试库只允许本地相对重定位，不加载任何真实动态库，不解析系统导入。
    for section in image.sections:
        if section["type"] != 4 or not section["flags"] & 2:
            continue
        require(section["entry_size"] == 24 and section["size"] % 24 == 0, "重定位表形状错误")
        for off in range(section["offset"], section["offset"] + section["size"], 24):
            address, info, addend = struct.unpack_from("<QQq", image.data, off)
            require(info == 1027 and 0 <= address <= 0x100000 - 8 and 0 <= addend < 0x100000,
                    "试验出现外部或越界重定位")
            machine.mem_write(BASE + address, struct.pack("<Q", BASE + addend))
    machine.mem_map(STACK, 0x10000)
    machine.mem_map(STOP, 4096)
    machine.reg_write(reg.UC_ARM64_REG_SP, STACK + 0xff00)
    machine.reg_write(reg.UC_ARM64_REG_X0, case)
    machine.reg_write(reg.UC_ARM64_REG_LR, STOP)
    machine.emu_start(BASE + entry, STOP, count=500000)
    require(machine.reg_read(reg.UC_ARM64_REG_PC) == STOP, "试验超出指令预算")
    line = machine.reg_read(reg.UC_ARM64_REG_X0)
    labels = CASES if cases is None else cases
    require(line == 0, f"场景「{labels[case]}」失败，C++ 测试行号 {line}")


def run_trial(path):
    raw = Path(path).read_bytes()
    image = ElfImage(raw)
    entry = trial_entry(image)
    for case in CASES:
        run_case(image, entry, case)
    return {"诊断入口场景": len(CASES), "结果": "全部通过", "试验库SHA256": digest(raw),
            "边界": "真实生产入口，系统/线程/Binder 为替身；不是设备验收",
            "安装许可": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", required=True, help="交叉编译的独立测试库，不是 APK 原型库")
    args = parser.parse_args()
    try:
        print(json.dumps(run_trial(args.elf), ensure_ascii=False, indent=2))
    except (AuditError, OSError, ImportError) as error:
        print(f"诊断离线验证失败：{error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
