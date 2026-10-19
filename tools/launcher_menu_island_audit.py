#!/usr/bin/env python3
"""四项菜单功能「调用点单字替换」蹦床的离线语义审计。

只读本地宿主 `libapp.so` 与交叉汇编得到的蹦床 `.o`，在隔离的 ARM64 模拟器里
执行一次真实的「调用点被改成 `bl 蹦床`」流程：

    caller --bl 蹦床--> island --br--> 原目标(替身) --ret(x30)--> 接续指令

检查的是**蹦床的透明性**：x30 必须仍是「调用点+4」，Dart 活寄存器、Dart 栈指针、
条件标志、向量寄存器逐项保持，只有 x16/x17 允许变化。再用故障注入证明这些检查
不是摆设。不生成 SO、不链接任何库、不修改宿主 ELF、不接触设备。
`installPermitted` 必须为 false，通过也不等于安装许可。

目标函数的**函数体**没有在隔离环境里执行：它内部依赖 Dart 运行期对象，无法在不
伪造整套运行时的前提下跑通。这里只把目标入口当作替身，因此本工具证明的是蹦床
自身的控制流与寄存器契约，不是四项功能的端到端行为。
"""

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_native_audit import (AuditError, ElfImage, digest, number,  # noqa: E402
                                   require)
from launcher_shortcut_count_emulate import Arm64Object  # noqa: E402


DEFAULT_CONTRACT = (Path(__file__).resolve().parents[1]
                    / "app/src/test/resources/launcher-menu-island-contract.json")
POLICY = "menu-call-site-island-offline-v1"
ISLAND_SYMBOL = "launcher_menu_island"
ORIGINAL_SYMBOL = "launcher_menu_original"
RET = 0xD65F03C0
BRANCH_RANGE = 1 << 27
# 模拟器布局：合成调用方、蹦床页、目标替身、Dart/C 栈、停止桩各占独立页。
# 蹦床必须落在调用点的 26 位立即数范围内（±128MB），这里刻意只隔 16MB；
# 距离一旦超限，`bl 蹦床` 根本无法编码，这正是落点前提的现实含义。
CALLER = 0x10000000
ISLAND = 0x11000000
TARGET = 0x12000000
SLOT = 0x13000000
DART_STACK = 0x40000000
C_STACK = 0x41000000
STOP = 0x50000000
PAGE = 0x1000


def branch26(pc, target, link):
    delta = target - pc
    require(delta % 4 == 0, "分支目标未按 4 字节对齐")
    require(-BRANCH_RANGE <= delta < BRANCH_RANGE, "分支目标超出 26 位立即数范围")
    return (0x94000000 if link else 0x14000000) | ((delta // 4) & 0x03FFFFFF)


def encode_adrp(word, pc, target):
    # 必须保留原机器字的目标寄存器 Rd：早期版本把它清零，结果编成 `adrp x0`，
    # 蹦床拿不到原目标地址。这里显式校验操作码并回填 Rd。
    require(word & 0x9F000000 == 0x90000000, "高位重定位不是 ADRP")
    delta = (target >> 12) - (pc >> 12)
    require(-(1 << 20) <= delta < (1 << 20), "ADRP 重定位超出可达范围")
    bits = delta & 0x1FFFFF
    return (word & 0x1F) | 0x90000000 | ((bits & 3) << 29) | ((bits >> 2) << 5)


def encode_add_lo12(word, target):
    require(word & 0xFFC00000 == 0x91000000, "低位重定位不是 64 位未移位 ADD")
    return (word & ~0x003FFC00) | ((target & 0xFFF) << 10)


def materialize(obj, address, externals):
    """按已审核的三类重定位把蹦床搬进模拟器地址；外部符号集合必须完全一致。"""
    require({name for _, _, name in obj.relocations} == set(externals),
            "蹦床对象的外部符号集合与预期不符")
    code = bytearray(obj.code)
    for offset, kind, name in obj.relocations:
        word, = struct.unpack_from("<I", code, offset)
        pc, target = address + offset, externals[name]
        if kind == 275:
            encoded = encode_adrp(word, pc, target)
        elif kind == 277:
            encoded = encode_add_lo12(word, target)
        elif kind in (282, 283):
            encoded = branch26(pc, target, kind == 283)
        else:
            raise AuditError("出现未审核的 ARM64 重定位类型：%d" % kind)
        struct.pack_into("<I", code, offset, encoded)
    return bytes(code)


class Trial:
    """一次隔离执行：合成调用方 -> 蹦床 -> 目标替身 -> 接续指令。"""

    def __init__(self):
        try:
            import unicorn as uc
            from unicorn import arm64_const as reg
        except ImportError as error:
            raise AuditError("缺少离线仿真依赖，请在本地虚拟环境安装 "
                             "requirements-launcher-emulation.txt") from error
        self.uc, self.reg = uc, reg
        self.machine = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
        self.gprs = [getattr(reg, "UC_ARM64_REG_X%d" % i) for i in range(31)]
        self.qregs = [getattr(reg, "UC_ARM64_REG_Q%d" % i) for i in range(32)]
        for address in (CALLER, ISLAND, TARGET, SLOT, DART_STACK, C_STACK, STOP):
            self.machine.mem_map(address, PAGE)
        self.machine.mem_write(TARGET, struct.pack("<I", RET))
        # 原目标指针槽：模块在写补丁字之前填好它，蹦床只读这个槽。
        self.machine.mem_write(SLOT, struct.pack("<Q", TARGET))
        self.machine.mem_write(STOP, struct.pack("<I", RET))
        self.reg_at = {}
        self.failure = None
        self.writes = []
        self.machine.hook_add(uc.UC_HOOK_CODE, self._on_code)
        self.machine.hook_add(uc.UC_HOOK_MEM_WRITE, self._on_write)

    def _on_code(self, machine, address, size, context):
        if address in (TARGET, CALLER + 4, STOP):
            self.reg_at.setdefault(address, self.snapshot())
        if address == STOP:
            machine.emu_stop()

    def _on_write(self, machine, access, address, size, value, context):
        # 蹦床是叶函数：它自己不应写任何内存；目标替身只执行 `ret`，也不写。
        # 只允许测试装置自己写地址，任何其它写入都说明蹦床偏离了契约。
        if SLOT <= address < SLOT + PAGE or TARGET <= address < TARGET + PAGE:
            self.writes.append(address)
            return
        self.failure = "蹦床执行期间发生了内存写入：%#x" % address
        machine.emu_stop()

    def snapshot(self):
        return {"gpr": [self.machine.reg_read(r) for r in self.gprs],
                "q": [self.machine.reg_read(r) for r in self.qregs],
                "sp": self.machine.reg_read(self.reg.UC_ARM64_REG_SP),
                "nzcv": self.machine.reg_read(self.reg.UC_ARM64_REG_NZCV)}

    def run(self, code, call_word, flags, dart, sentinel):
        m = self.machine
        m.mem_write(ISLAND, code)
        # 调用点：`bl 蹦床`；接续指令：`ret`（模拟“返回调用点之后继续执行”）。
        m.mem_write(CALLER, struct.pack("<II", call_word, RET))
        for index, register in enumerate(self.gprs):
            value = sentinel[index]
            m.reg_write(register, value)
        # 调用方按约定给出 Dart 栈指针、帧指针与返回地址。
        m.reg_write(self.gprs[15], dart["x15"])
        m.reg_write(self.gprs[29], dart["x29"])
        for index, register in enumerate(self.qregs):
            m.reg_write(register, dart["q"][index])
        m.reg_write(self.reg.UC_ARM64_REG_NZCV, flags << 28)
        m.reg_write(self.reg.UC_ARM64_REG_SP, C_STACK + 0x800)
        m.reg_write(self.reg.UC_ARM64_REG_X30, STOP)
        m.reg_write(self.reg.UC_ARM64_REG_PC, CALLER)
        try:
            m.emu_start(CALLER, STOP, count=4096)
        except self.uc.UcError as error:
            raise AuditError("穿越蹦床执行被模拟器拒绝：%s" % error) from error
        if self.failure is not None:
            raise AuditError(self.failure)
        require(self.reg_at.get(TARGET) is not None, "目标替身没有被执行到")
        require(self.reg_at.get(CALLER + 4) is not None, "控制流没有回到接续指令")


def call_word(target):
    return branch26(CALLER, ISLAND, True) if target == ISLAND else target


def verify_island(obj, contract):
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY
            and contract["installPermitted"] is False,
            "蹦床合同版本、策略或安装许可字段不符")
    island = contract["island"]
    require(island["symbol"] == ISLAND_SYMBOL, "蹦床符号名不符")
    require(len(obj.code) == island["size"] and digest(obj.code) == island["sha256"],
            "蹦床字节长度或摘要与合同不符")
    require(obj.relocations == [tuple(item) for item in island["relocations"]],
            "蹦床重定位集合与合同不符")
    referenced = {name for _, _, name in obj.relocations}
    require(referenced == {ORIGINAL_SYMBOL},
            "蹦床必须且只能引用原目标符号：%s" % sorted(referenced))
    words = struct.unpack("<%dI" % (len(obj.code) // 4), obj.code)
    require(list(words) == [number(item) for item in island["words"]],
            "蹦床机器字与合同逐条不符")
    require(words[-1] in (0xD61F0200, 0xD61F0220, 0xD61F0240),
            "蹦床最后一条必须是寄存器尾跳 br x16/x17/x18")
    require(RET not in words, "叶蹦床不得出现 RET")
    require(not any(word & 0xFC000000 == 0x94000000 for word in words),
            "叶蹦床不得出现任何 BL")
    return {"符号": ISLAND_SYMBOL, "字节数": len(obj.code),
            "机器字": [hex(word) for word in words],
            "重定位": [[item[0], item[1], item[2]] for item in obj.relocations],
            "尾跳": "br（不写 x30）"}


def sentinel_registers():
    """哨兵值：除 x15/x29/x30 外全部给出可区分内容，便于逐项比对。"""
    return [0x1BAD000000000000 | (i << 32) | 0x5A5A for i in range(31)]


def run_matrix(obj, contract, flags_matrix):
    """对每条候选调用点、每个条件标志组合执行一次穿越蹦床流程。"""
    top = DART_STACK + 0x800
    dart = {"x15": top, "x29": top + 0x100, "q": [0x8F3A5C << 32 | i for i in range(32)]}
    cases = []
    for site in contract["sites"]:
        for flags in flags_matrix:
            trial = Trial()
            code = materialize(obj, ISLAND, {ORIGINAL_SYMBOL: SLOT})
            sentinel = sentinel_registers()
            sentinel[15], sentinel[29] = dart["x15"], dart["x29"]
            trial.run(code, branch26(CALLER, ISLAND, True), flags, dart, sentinel)
            entry = trial.reg_at[TARGET]
            require(entry["gpr"][30] == CALLER + 4,
                    "进入目标时 x30 不是「调用点+4」：%#x" % entry["gpr"][30])
            require(entry["gpr"][15] == dart["x15"], "进入目标时 Dart 栈指针被改动")
            require(entry["gpr"][29] == dart["x29"], "进入目标时帧指针被改动")
            require(entry["nzcv"] == flags << 28, "进入目标时条件标志被改动")
            for index in range(31):
                # x16/x17 是允许的暂存；x30 由 `BL 蹦床` 正常改写，单独核对。
                if index in (16, 17, 30):
                    continue
                require(entry["gpr"][index] == sentinel[index],
                        "蹦床改动了不该改的寄存器 x%d" % index)
            for index in range(32):
                require(entry["q"][index] == dart["q"][index],
                        "蹦床改动了向量寄存器 Q%d" % index)
            back = trial.reg_at[CALLER + 4]
            require(back["gpr"][30] == CALLER + 4, "返回后 x30 不再是「调用点+4」")
            require(back["gpr"][15] == dart["x15"], "返回后 Dart 栈指针未恢复")
            require(back["sp"] == C_STACK + 0x800, "C 栈指针被改动")
            cases.append({"功能": site["feature"], "调用点": site["callOffset"],
                          "标志": flags, "x30": "保持调用点+4", "仅 x16/x17 可变": True})
    return cases


def negative_controls(obj, contract, flags):
    """主动破坏蹦床或调用字，要求流程必须失败，避免只验证自己的期望值。"""
    site = contract["sites"][0]
    external = {ORIGINAL_SYMBOL: SLOT}
    probes = []

    def expect_failure(label, code, call, why):
        trial = Trial()
        sentinel = sentinel_registers()
        dart = {"x15": DART_STACK + 0x800, "x29": DART_STACK + 0x900,
                "q": [0x8F3A5C << 32 | i for i in range(32)]}
        try:
            trial.run(code, call, flags, dart, sentinel)
        except AuditError:
            probes.append({"对照": label, "已检出": True})
            return
        # 没有抛错时，逐项确认“破坏”确实造成了可观察差异。
        entry = trial.reg_at.get(TARGET)
        broken = entry is None or entry["gpr"][30] != CALLER + 4
        require(broken, "故障对照漏检：%s（%s）" % (label, why))
        probes.append({"对照": label, "已检出": True})

    # 1) 尾跳写成 `blr x16`：x30 被改成蹦床内地址，原目标会返回到错误位置。
    damaged = bytearray(obj.code)
    word, = struct.unpack_from("<I", damaged, len(damaged) - 4)
    struct.pack_into("<I", damaged, len(damaged) - 4, word | 0x3F000000)
    expect_failure("尾跳写成 blr 破坏 x30", materialize_bytes(damaged, obj, external),
                   branch26(CALLER, ISLAND, True), "x30 被写坏")
    # 2) 调用点写成 `b 蹦床`：x30 不会被设置，接续指令拿不到正确返回地址。
    expect_failure("调用点写成 B 而不是 BL",
                   materialize(obj, ISLAND, external), branch26(CALLER, ISLAND, False),
                   "x30 未被设置")
    # 3) 蹦床指向错误目标：控制流到达的是别的地址。
    wrong = {ORIGINAL_SYMBOL: TARGET + 0x100}
    expect_failure("蹦床指向错误目标", materialize(obj, ISLAND, wrong),
                   branch26(CALLER, ISLAND, True), "落到了未映射地址")
    return probes


def materialize_bytes(code, obj, externals):
    payload = bytearray(obj.code)
    payload[:len(code)] = bytes(code[:len(obj.code)])
    patched = type("Patched", (), {"code": bytes(payload),
                                   "relocations": obj.relocations})()
    return materialize(patched, ISLAND, externals)


def verify_host(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")
    sites = contract["sites"]
    require(len(sites) == contract["counts"]["sites"], "调用点数量与合同不符")
    seen = set()
    for site in sites:
        offset = number(site["callOffset"])
        require(offset not in seen, "候选调用点重复：%#x" % offset)
        seen.add(offset)
        word = struct.unpack("<I", image.code(offset, 4))[0]
        require(word == number(site["word"]), "调用点机器字与合同不符：%#x" % offset)
        delta = word & 0x03FFFFFF
        if delta & 0x02000000:
            delta -= 0x04000000
        require((word & 0xFC000000 == 0x94000000) and offset + delta * 4 == number(site["target"]),
                "调用点不是指向既定目标的 BL：%#x" % offset)
        target = number(site["target"])
        require(struct.unpack("<I", image.code(target, 4))[0] == number(site["entryWord"]),
                "目标入口机器字与合同不符：%s" % site["targetName"])
        for index, expected_word in enumerate(site["prologueWords"]):
            require(struct.unpack("<I", image.code(target + index * 4, 4))[0]
                    == number(expected_word),
                    "目标入口序言与合同不符：%s" % site["targetName"])
    return {"调用点数": len(sites), "序言均为既定的 Dart AOT 形态": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--object", type=Path, required=True, help="交叉汇编的蹦床 .o")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="蹦床语义合同 JSON")
    parser.add_argument("--flags", default="0,1,7,8,F",
                        help="要覆盖的条件标志（十六进制，逗号分隔）")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        obj = Arm64Object(args.object.read_bytes(), ISLAND_SYMBOL)
        flags = [int(item, 16) for item in args.flags.split(",")]
        require(flags and all(0 <= value < 16 for value in flags), "条件标志矩阵不合法")
        host = verify_host(image, contract)
        island = verify_island(obj, contract)
        cases = run_matrix(obj, contract, flags)
        probes = negative_controls(obj, contract, flags[0])
        require(len(probes) == contract["counts"]["negativeControls"], "故障对照数量不符")
        require(len(cases) == len(contract["sites"]) * len(flags), "用例数不符")
        print(json.dumps({"宿主合同": host, "蹦床": island, "穿越用例": len(cases),
                          "用例样例": cases[:2], "故障对照": probes, "安装许可": False,
                          "限制": "离线语义试验：目标函数体未执行（依赖 Dart 运行期对象），"
                                  "本工具只证明蹦床自身的控制流与寄存器契约；不证明安装窗口"
                                  "无并发执行、新代码缓存同步、宿主映射生命周期，也不证明"
                                  "四项功能端到端可用"},
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        print("蹦床语义审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
