#!/usr/bin/env python3
"""只读验证留存框架的模块中继回填顺序；仅在模拟器内执行样本指令。"""

import argparse
import json
import struct
from pathlib import Path

from launcher_backend_audit import (
    CONTRACT, COPY_VARIANTS, FRAMEWORK_BASE, PATCH, SOURCE, STACK,
    STOP, STUBS, REPLACEMENT, PatchMachine, pointer_value, relocations, verify_samples,
)
from launcher_native_audit import (
    AuditError, DEFAULT_CONTRACT, ElfImage, require, verify_contract,
)


OBJECTS = 0x9000000
API = OBJECTS
ORIGINAL = OBJECTS + 0x1000
ENTRY = OBJECTS + 0x2000
ROUTING = OBJECTS + 0x3000
BUFFER = OBJECTS + 0x4000
RELAY = OBJECTS + 0x5000
REGISTRY = OBJECTS + 0x6000

# 摘要之外钉住影响回填顺序的关键指令，不能只刷新合同摘要改变结论。
PUBLICATION_WORDS = {
    0x66794: (0x94009821, 0xD0000968, 0xF9050900),
    0x8D698: (0xF0000828, 0xF9450900, 0xD63F02A0),
    0x8D76C: (0xAA0203E3, 0xAA0103E2, 0xF9409D08),
    0x8936C: (0xAA0303F3, 0xD63F0100, 0xAA0003E8, 0xF100001F,
              0x1A9F17E0, 0xF9000268),
    0x7D298: (0xA9BF7FFE, 0xF0000728, 0x910023E2, 0xF9448D08, 0xD63F0100),
    0x9D674: (0xF94012C8, 0xF9000288, 0xB0000688, 0xAA1303E0,
              0xF946DD08, 0xD63F0100),
    0x9DE74: (0xF9400008, 0xF9400901, 0xD61F0020),
}
PUBLICATION_SLOTS = {
    0x168C88: 0x87A00, 0x168C90: 0x87A0C,
    0x169138: 0x89358, 0x169140: 0x7D298, 0x164918: 0x9D498,
    0x16EDB0: 0x9D764, 0x16EDB8: 0x9DE74, 0x16EDC8: 0x9D79C,
    0x1499A8: 0xA3B14, 0x1499B0: 0x557E0, 0x1499B8: 0x9DD20,
    0x16EE68: PATCH,
}


def verify_publication(framework, contract):
    require(contract["publication"]["pendingGates"] == [1, 3, 5]
            and contract["publication"]["moduleOriginalBeforePatch"] is False,
            "模块中继发布门槛不可放行")
    require(all(contract["publication"][key] == value for key, value in {
        "apiGlobal": "0x194a10", "hookFunction": "0x8d764",
        "internalBackupStore": "0x9d678", "moduleOriginalStore": "0x89380",
    }.items()), "发布合同与固定样本位置不一致")
    for address, words in PUBLICATION_WORDS.items():
        require(framework.code(address, len(words) * 4) == struct.pack("<" + "I" * len(words), *words),
                f"模块中继回填顺序指令变化：{address:#x}")
    _, packed = relocations(framework)
    for address, target in PUBLICATION_SLOTS.items():
        require(address in packed and pointer_value(framework, address) == target,
                f"模块 Hook API 调用链变化：{address:#x}")
    return {"模块入口取参": "0x66794 -> 0x194a10 -> 0x8d69c",
            "模块Hook调用链": "0x8d764 -> 0x89358 -> 0x7d298 -> 0x9d498",
            "内部回填": "0x9d678 写包装器栈内临时槽，不是模块 original",
            "提交调用链": "0x9d688 -> 0x9de74 -> 0x9dd20 -> 0xa5324",
            "模块回填": "0x89380，底层提交并返回之后",
            "未通过门槛": [1, 3, 5], "安装许可": False}


def verify_observations(observations, initial):
    require([o["阶段"] for o in observations] ==
            ["内部回填后", "提交入口", "补丁完成", "模块回填前", "模块回填后"],
            "发布观察阶段不完整或顺序变化")
    for item in observations:
        require(item["内部中继"] == RELAY, "内部中继未先回填临时槽")
    require(all(o["模块中继"] == initial for o in observations[:-1]),
            "模块中继不再是底层返回后才回填")
    require(observations[-1]["模块中继"] == RELAY, "模块中继最终没有回填")
    require([o["完整补丁"] for o in observations] == [False, False, True, True, True],
            "目标补丁与模块回填的次序变化")


def verify_publication_window(window):
    """补丁例程返回到模块槽写入之间必须只有固定直线指令，且没有同步原语。"""
    require(len(window) > 0, "未记录补丁返回后的发布窗口")
    actual = [f"{entry[0]:#x}:{entry[1]:#010x}" for entry in window]
    expected = [f"{offset:#x}:{word:#010x}" for offset, word in EXPECTED_WINDOW]
    require(actual == expected, f"发布窗口指令序列变化：{actual}")
    for offset, word in window:
        require(word not in SYNC_WORDS, f"发布窗口内出现同步原语：{offset:#x}")


# 样本内仅有的独占访存编码（ldaxr/stlxr），用于证明窗口内没有同步原语。
SYNC_WORDS = {0x885FFC89, 0x880AFC89, 0x88DFFC88}

# 从 0xa5324 返回（0x9dd70）之后到模块槽写入（0x89380）之前执行的全部指令。
# 这 43 条覆盖 routing 返回值处理、routing 登记、包装器返回路径与模块外层判断；
# 其中没有任何同步原语、也没有回到补丁例程的边。序号是样本相对地址。
EXPECTED_WINDOW = (
    (0x9DD70, 0x7100001F), (0x9DD74, 0xD503201F), (0x9DD78, 0x10558F89), (0x9DD7C, 0x1A9F07E8),
    (0x9DD80, 0xF8685928), (0x9DD84, 0xD61F0100), (0x9DD88, 0x52800028),
    (0x9DD8C, 0xD503201F), (0x9DD90, 0x10558F49), (0x9DD94, 0xF8687929),
    (0x9DD98, 0xAA1F03E8), (0x9DD9C, 0xD61F0120), (0x9DE68, 0xA9414FF4),
    (0x9DE6C, 0xA8C257FE), (0x9DE70, 0xD65F03C0), (0x9D68C, 0xF946CAE8),
    (0x9D690, 0xD63F0100), (0xA4524, 0xF81E0FFE), (0x9D694, 0xB0000688),
    (0x9D698, 0xAA1603E1), (0x9D69C, 0xF946E108), (0x9D6A0, 0xD63F0100),
    (0xA4688, 0xA9BF07FE), (0x9D6A4, 0x2A1F03E0), (0x9D6A8, 0x1400002C),
    (0x9D758, 0x940272D6), (0x13A2B0, 0xA9434FF4), (0x13A2B4, 0xA94257F6),
    (0x13A2B8, 0xA9415FF8), (0x13A2BC, 0xD65F03C0), (0x9D75C, 0xF84407FE),
    (0x9D760, 0xD65F03C0), (0x7D2AC, 0x7100001F), (0x7D2B0, 0xD503201F),
    (0x7D2B4, 0x1063F529), (0x7D2B8, 0x1A9F17E8), (0x7D2BC, 0xF8685928),
    (0x7D2C0, 0xD61F0100), (0x7D2CC, 0xF94007E0), (0x7D2D0, 0xF84107FE),
    (0x7D2D4, 0xD65F03C0), (0x89374, 0xAA0003E8), (0x89378, 0xF100001F),
    (0x8937C, 0x1A9F17E0),
)


class PublicationMachine(PatchMachine):
    """真实 API/包装/Dobby 控制流和提交；分配、登记、重定位生成明确使用替身。"""

    def __init__(self, framework, libc, stock, variant, page_size, crossing, initial):
        self.phase = "构造"
        self.initial = initial
        self.backup = None
        self.observations = []
        self.visited = set()
        self.allocations = []
        self.stub_events = []
        self.native_argument = None
        self.resolved_name = None
        self.window = []
        self.in_window = False
        super().__init__(framework, libc, stock, variant, page_size, crossing, "none")
        self.machine.mem_map(OBJECTS, 0x10000)
        self.put(FRAMEWORK_BASE + 0x18B570, API)
        self.put(ORIGINAL, initial)
        self.put(FRAMEWORK_BASE + 0x1691E0, STUBS + 32)

    def put(self, address, value):
        self.machine.mem_write(address, struct.pack("<Q", value))

    def get(self, address):
        return struct.unpack("<Q", self.machine.mem_read(address, 8))[0]

    def observe(self, stage):
        require(self.backup is not None, "内部中继槽尚未记录")
        self.observations.append({
            "阶段": stage, "内部中继": self.get(self.backup),
            "模块中继": self.get(ORIGINAL),
            "完整补丁": bytes(self.machine.mem_read(self.target, 24)) == self.patch,
        })

    def stub_return(self, name, value):
        self.stub_events.append(name)
        self.return_from_stub(value)

    def on_code(self, machine, address, size, data):
        pc = address - FRAMEWORK_BASE
        self.visited.add(pc)
        if self.phase == "构造" and address == STUBS + 16:
            require([machine.reg_read(r) for r in self.gprs[:3]] == [API, 4096, 1],
                    "API 页只读请求变化")
            machine.mem_protect(API, 4096, self.uc.UC_PROT_READ)
            self.stub_return("API 页权限替身", 0)
            return
        if self.phase == "取参" and address == STUBS + 32:
            name_at = machine.reg_read(self.gprs[1])
            self.resolved_name = bytes(machine.mem_read(name_at, 12)).split(b"\0", 1)[0]
            require(self.resolved_name == b"native_init", "实际符号解析名不是 native_init")
            self.stub_return("dlsym 替身", STUBS + 48)
            return
        if self.phase == "取参" and address == STUBS + 48:
            self.native_argument = machine.reg_read(self.gprs[0])
            machine.emu_stop()
            return
        if self.phase == "调用":
            if pc == 0x9D498:
                self.backup = machine.reg_read(self.gprs[2])
                require(STACK <= self.backup < STACK + 0x10000 and self.backup != ORIGINAL,
                        "Dobby 收到的不再是包装器栈内槽")
                require(self.get(self.backup) == 0, "栈内槽没有初始化为零")
            elif pc == 0x89380:
                self.in_window = False
            elif self.in_window:
                self.window.append((pc, struct.unpack("<I", machine.mem_read(address, 4))[0]))
            elif pc == 0x9DD70:
                # 补丁例程已经返回，窗口从下一条指令开始，到模块槽写入为止。
                self.in_window = True
                self.window.append((pc, struct.unpack("<I", machine.mem_read(address, 4))[0]))
            if pc == 0xA4DF4:
                self.stub_return("页大小查询替身", self.page_size)
                return
            elif pc == 0xA5010:
                expected = [self.target & -self.page_size, self.page_size, 4]
                require([machine.reg_read(r) for r in self.gprs[:3]] == expected,
                        "目标页前置改页参数变化")
                self.stub_return("目标页前置改页成功替身", 1)
                return
            elif pc == 0xA4524:
                self.stub_return("登记表获取替身", REGISTRY)
                return
            elif pc == 0xA4598:
                require([machine.reg_read(r) for r in self.gprs[:2]] == [REGISTRY, self.target],
                        "查重参数变化")
                self.stub_return("目标未登记替身", 0)
                return
            elif pc == 0x13CD64:
                index = len(self.allocations)
                require(index < 2 and machine.reg_read(self.gprs[0]) == (312, 64)[index],
                        "分配对象大小或次数变化")
                result = (ENTRY, ROUTING)[index]
                self.allocations.append(result)
                self.stub_return("对象分配替身", result)
                return
            elif pc == 0xA4C38:
                require([machine.reg_read(r) for r in self.gprs[:3]] == [ENTRY, 0, self.target],
                        "entry 构造参数变化")
                self.put(ENTRY + 0x10, self.target)
                self.stub_return("entry 初始化替身", ENTRY)
                return
            elif pc == 0xA3B14:
                require(self.get(ROUTING) == FRAMEWORK_BASE + 0x1499A8
                        and self.get(ROUTING + 8) == ENTRY
                        and self.get(ROUTING + 0x38) == REPLACEMENT,
                        "真实 routing 构造结果变化")
                self.put(ENTRY + 0x20, RELAY)
                self.put(ROUTING + 0x28, BUFFER)
                self.put(BUFFER + 8, SOURCE)
                self.put(BUFFER + 16, SOURCE + 24)
                self.stub_return("重定位与补丁缓冲生成替身", 0)
                return
            elif pc == 0xA4688:
                require([machine.reg_read(r) for r in self.gprs[:2]] == [REGISTRY, ENTRY],
                        "登记参数变化")
                self.stub_return("登记完成替身", 0)
                return
            elif pc in (0x9D67C, PATCH, 0x9DD70, 0x89380, 0x89384):
                self.observe({0x9D67C: "内部回填后", PATCH: "提交入口", 0x9DD70: "补丁完成",
                              0x89380: "模块回填前", 0x89384: "模块回填后"}[pc])
        super().on_code(machine, address, size, data)

    def execute(self, start):
        m = self.machine
        m.reg_write(self.reg.UC_ARM64_REG_SP, STACK + 0xFF00)
        m.reg_write(self.reg.UC_ARM64_REG_LR, STOP)
        try:
            m.emu_start(start, STOP, count=20000)
        except self.uc.UcError as error:
            raise AuditError(f"{self.phase} 阶段样本执行失败，PC={m.reg_read(self.reg.UC_ARM64_REG_PC):#x}："
                             f"{error}") from error
        expected = STUBS + 48 if self.phase == "取参" else STOP
        require(m.reg_read(self.reg.UC_ARM64_REG_PC) == expected, "调用链超出指令预算或停止位置变化")

    def run(self):
        self.execute(FRAMEWORK_BASE + 0x66790)
        require(self.get(FRAMEWORK_BASE + 0x194A10) == API, "API 构造结果未写全局槽")
        require(struct.unpack("<I", self.machine.mem_read(API, 4))[0] == 2
                and self.get(API + 8) == FRAMEWORK_BASE + 0x8D764
                and self.get(API + 16) == FRAMEWORK_BASE + 0x8D744, "Native API 布局或指针变化")
        self.phase = "取参"
        self.machine.reg_write(self.gprs[19], FRAMEWORK_BASE + 0x146B90)
        self.machine.reg_write(self.gprs[23], FRAMEWORK_BASE + 0x169000)
        self.machine.reg_write(self.gprs[25], 1)
        self.execute(FRAMEWORK_BASE + 0x8D450)
        require(self.native_argument == API, "native_init 实际取参不是 API 页面")
        self.phase = "调用"
        for register, value in zip(self.gprs, (self.target, REPLACEMENT, ORIGINAL)):
            self.machine.reg_write(register, value)
        self.execute(self.get(API + 8))
        verify_observations(self.observations, self.initial)
        verify_publication_window(self.window)
        require(self.machine.reg_read(self.gprs[0]) == 0, "模块 Hook API 未返回成功")
        require({0x8D6A0, 0x8D780, 0x89370, 0x7D2A8, 0x9D678, 0x9D688,
                 0x9DE74, 0x9DD6C, PATCH, 0x89380}.issubset(self.visited),
                "未实际执行全部关键调用点")
        require(len(self.snapshots) == 2, "提交没有经过两条真实 libc 存储")
        return {"初始模块中继": self.initial, "观察": self.observations,
                "发布窗口": [f"{offset:#x}:{word:#010x}" for offset, word in self.window],
                "替身": sorted(set(self.stub_events)), "返回值": 0}


def emulate_publication(framework, libc, stock):
    cases, examples = 0, []
    for variant in COPY_VARIANTS:
        for page_size in (4096, 16384):
            for crossing in (False, True):
                for initial in (0, 0x12345678):
                    result = PublicationMachine(framework, libc, stock, variant, page_size, crossing, initial).run()
                    cases += 1
                    if variant == "simd" and page_size == 4096 and not crossing:
                        examples.append(result)
    return {"场景数": cases, "观察示例": examples,
            "错误指令对照": negative_controls(framework, libc, stock),
            "限制": "离线顺序证据；对象分配/前置改页/登记/重定位生成为替身，缓存仅记录；"
                    "未执行完整加载流程、真实中继或并发线程，不证明实机当前映像一致",
            "未通过门槛": [1, 3, 5], "安装许可": False}


def negative_controls(framework, libc, stock):
    # 对照只修改各自模拟器的内存，不改样本文件，也不执行真实进程中的补丁。
    results = []
    for name, address, word, expected in (
        ("取消模块回填", 0x89380, 0xD503201F, "模块中继最终没有回填"),
        ("清空内部回填", 0x9D678, 0xF900029F, "内部中继未先回填临时槽"),
        ("直接传模块槽", 0x7D2A0, 0xAA1303E2, "Dobby 收到的不再是包装器栈内槽"),
        ("伪造补丁提交成功", 0x9DD6C, 0x2A1F03E0, "发布观察阶段不完整或顺序变化"),
    ):
        trial = PublicationMachine(framework, libc, stock, "simd", 4096, False, 0)
        trial.machine.mem_write(FRAMEWORK_BASE + address, struct.pack("<I", word))
        try:
            trial.run()
        except AuditError as error:
            require(expected in str(error), f"对照 {name} 未按预期检出：{error}")
            results.append({"对照": name, "检出": str(error)})
        else:
            raise AuditError(f"错误指令对照漏检：{name}")
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework", required=True, help="本地留存框架 SO")
    parser.add_argument("--libc", required=True, help="本地留存 libc SO")
    parser.add_argument("--host-elf", required=True, help="固定宿主 libapp.so")
    parser.add_argument("--emulate", action="store_true", help="仅在 Unicorn 地址空间执行")
    args = parser.parse_args()
    try:
        framework, libc, host = [ElfImage(Path(p).read_bytes()) for p in
                                 (args.framework, args.libc, args.host_elf)]
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        verify_contract(host, json.loads(DEFAULT_CONTRACT.read_text(encoding="utf-8")))
        verify_samples(framework, libc, contract)
        result = {"静态审计": verify_publication(framework, contract)}
        if args.emulate:
            result["离线执行"] = emulate_publication(framework, libc, host.code(0x114701C, 24))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, KeyError, OSError, ImportError, struct.error) as error:
        print(f"模块中继发布审计失败：{error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
