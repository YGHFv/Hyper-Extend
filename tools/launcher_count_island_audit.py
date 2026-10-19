#!/usr/bin/env python3
"""数量点专用蹦床的离线语义审计。

只读本地宿主 `libapp.so` 与交叉汇编得到的 `.o`，在隔离的 ARM64 模拟器里执行真实的
「调用点被改成 `bl 蹦床`」流程：

    caller --bl 蹦床--> 蹦床(x30 := 岛内 .Lcount_after) --br--> 原 getter(替身)
           --ret(x30)--> .Lcount_after --按一次性原子字改写 w0--> br 调用点+4

与 `launcher_menu_island_audit.py` 证明的「叶蹦床」不同，本蹦床**必须**改写 `x30`：
被替换的那条指令本来就是 `BL 原 getter`，所以取指进入 getter 时 `x30` 本来就不是
「调用点+4」，让 getter 返回到岛内接续点属于同一级语义。检查项因此分成三段：

1. 进入 getter 时只允许 `x16/x17/x30` 与哨兵不同（x30 == 岛内接续点），
   Dart 栈 `x15`、帧指针 `x29`、其余通用寄存器与全部向量寄存器逐项保持；
2. 回到接续指令（调用点+4）时 `x30` 必须重新等于调用点+4，只有 `x16/x17` 与
   `x0` 允许变化，`x0` 必须等于「状态字在 5..20 时取状态字、否则取原 getter 返回值」；
3. 全程除测试装置外零内存写入（三个数据槽只读）。

条件标志**不在**要求范围内：被替换的本身就是一次函数调用，原 getter 也会破坏
`NZCV`，所以这里不承诺保留，审计也不检查它。

本工具不生成 SO、不链接任何库、不修改宿主 ELF、不接触设备。`installPermitted`
必须为 false，通过也不等于安装许可。
"""

import argparse
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from launcher_menu_island_audit import encode_add_lo12, encode_adrp, branch26  # noqa: E402
from launcher_native_audit import AuditError, ElfImage, digest, number, require  # noqa: E402
from launcher_shortcut_count_emulate import Arm64Object  # noqa: E402


DEFAULT_CONTRACT = (Path(__file__).resolve().parents[1]
                    / "app/src/test/resources/launcher-count-island-contract.json")
POLICY = "count-call-site-island-offline-v1"
ISLAND_SYMBOL = "launcher_count_island"
ORIGINAL_SYMBOL = "launcher_count_island_original"
STATE_SYMBOL = "launcher_count_island_state"
CONTINUATION_SYMBOL = "launcher_count_island_continuation"
EXTERNALS = (ORIGINAL_SYMBOL, STATE_SYMBOL, CONTINUATION_SYMBOL)
RET = 0xD65F03C0
NOP = 0xD503201F
PAGE = 0x1000

CALLER = 0x10000000
ISLAND = 0x11000000
GETTER = 0x12000000
SLOT_PAGE = 0x13000000
DART_STACK = 0x40000000
C_STACK = 0x41000000
STOP = 0x50000000

ORIGINAL_SLOT = SLOT_PAGE
CONTINUATION_SLOT = SLOT_PAGE + 8
STATE_SLOT = SLOT_PAGE + 0x10


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


def decode_adr(word):
    """解出 ADR 的相对立即数；非 ADR 直接失败。"""
    require(word & 0x9F000000 == 0x10000000, "不是 ADR 编码：%#x" % word)
    immlo = (word >> 29) & 3
    immhi = (word >> 5) & 0x7FFFF
    value = (immhi << 2) | immlo
    return value - (1 << 21) if value & (1 << 20) else value


def effective_value(state, stock):
    require(isinstance(state, int) and 0 <= state <= 0xFFFFFFFF, "状态必须为无符号 32 位整数")
    return state if 5 <= state <= 20 else stock


class Trial:
    """一次隔离执行：合成调用方 -> 数量蹦床 -> getter 替身 -> 接续指令。"""

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
        for address in (CALLER, ISLAND, SLOT_PAGE, DART_STACK, C_STACK, STOP):
            self.machine.mem_map(address, PAGE)
        self.machine.mem_map(GETTER, PAGE)
        self.machine.mem_write(GETTER, struct.pack("<I", RET))
        self.machine.mem_write(CONTINUATION_SLOT, struct.pack("<Q", CALLER + 4))
        self.machine.mem_write(STOP, struct.pack("<I", RET))
        self.reg_at = {}
        self.failure = None
        self.getter_calls = 0
        self.machine.hook_add(uc.UC_HOOK_CODE, self._on_code)
        self.machine.hook_add(uc.UC_HOOK_MEM_WRITE, self._on_write)

    def _on_code(self, machine, address, size, context):
        if address in (GETTER, CALLER + 4, STOP):
            self.reg_at.setdefault(address, self.snapshot())
        if address == GETTER:
            self.getter_calls += 1
            machine.reg_write(self.gprs[0], self.stock)
        if address in (CALLER + 4, STOP):
            machine.emu_stop()

    def _on_write(self, machine, access, address, size, value, context):
        # 蹦床只读三个数据槽；getter 替身只执行 `ret`。除测试装置自己的写入外，
        # 任何写入都说明蹦床偏离了「零内存写入」契约。
        if SLOT_PAGE <= address < SLOT_PAGE + PAGE or GETTER <= address < GETTER + PAGE:
            return
        self.failure = "蹦床执行期间发生了内存写入：%#x" % address
        machine.emu_stop()

    def snapshot(self):
        return {"gpr": [self.machine.reg_read(r) for r in self.gprs],
                "q": [self.machine.reg_read(r) for r in self.qregs],
                "sp": self.machine.reg_read(self.reg.UC_ARM64_REG_SP)}

    def run(self, code, call_word, state, stock, dart, sentinel):
        m = self.machine
        self.stock = stock
        m.mem_write(ISLAND, code)
        m.mem_write(ORIGINAL_SLOT, struct.pack("<Q", GETTER))
        m.mem_write(STATE_SLOT, struct.pack("<I", state))
        m.mem_write(CALLER, struct.pack("<II", call_word, RET))
        for index, register in enumerate(self.gprs):
            value = sentinel[index]
            m.reg_write(register, value)
        m.reg_write(self.gprs[15], dart["x15"])
        m.reg_write(self.gprs[29], dart["x29"])
        for index, register in enumerate(self.qregs):
            m.reg_write(register, dart["q"][index])
        m.reg_write(self.reg.UC_ARM64_REG_SP, C_STACK + 0x800)
        m.reg_write(self.reg.UC_ARM64_REG_X30, STOP)
        m.reg_write(self.reg.UC_ARM64_REG_PC, CALLER)
        try:
            m.emu_start(CALLER, STOP, count=4096)
        except self.uc.UcError as error:
            raise AuditError("穿越蹦床执行被模拟器拒绝：%s" % error) from error
        if self.failure is not None:
            raise AuditError(self.failure)
        require(self.getter_calls == 1, "getter 替身没有被恰好执行一次")
        require(self.reg_at.get(CALLER + 4) is not None, "控制流没有回到接续指令")


def sentinel_registers():
    return [0x2BAD000000000000 | (i << 32) | 0x5A5A for i in range(31)]


def dart_state():
    top = DART_STACK + 0x800
    return {"x15": top, "x29": top + 0x100,
            "q": [0x8F3A5C << 32 | i for i in range(32)]}


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
    require(referenced == set(EXTERNALS),
            "蹦床必须且只能引用三个已审核数据符号：%s" % sorted(referenced))
    words = struct.unpack("<%dI" % (len(obj.code) // 4), obj.code)
    require(list(words) == [number(item) for item in island["words"]],
            "蹦床机器字与合同逐条不符")
    require(words[0] & 0x9F000000 == 0x10000000 and decode_adr(words[0]) == island["afterOffset"],
            "首条指令必须是设置岛内接续点的 ADR")
    require(decode_adr(words[0]) % 4 == 0 and 0 < decode_adr(words[0]) < len(obj.code),
            "岛内接续点必须落在蹦床自己的代码范围内")
    require(words[-1] == 0xD61F03C0, "末条指令必须是 br x30")
    require(RET not in words, "蹦床不得出现 RET")
    require(not any(word & 0xFC000000 == 0x94000000 for word in words),
            "蹦床不得出现任何 BL")
    return {"符号": ISLAND_SYMBOL, "字节数": len(obj.code),
            "机器字": [hex(word) for word in words],
            "重定位": [[item[0], item[1], item[2]] for item in obj.relocations],
            "岛内接续点": "偏移 %d" % island["afterOffset"], "尾跳": "br x30（x30 由数据槽恢复）"}


def verify_host(image, contract):
    expected = contract["image"]
    require(len(image.data) == expected["size"], "宿主文件长度与合同不符")
    require(digest(image.data) == expected["sha256"], "宿主文件摘要与合同不符")
    require(image.build_id() == expected["buildId"], "宿主 Build ID 与合同不符")
    site = contract["site"]
    offset = number(site["callOffset"])
    word = struct.unpack("<I", image.code(offset, 4))[0]
    require(word == number(site["word"]), "调用点机器字与合同不符")
    delta = word & 0x03FFFFFF
    if delta & 0x02000000:
        delta -= 0x04000000
    require(word & 0xFC000000 == 0x94000000 and offset + delta * 4 == number(site["target"]),
            "调用点不是指向既定目标的 BL")
    require(offset + 4 == number(site["continuation"]), "接续指令必须是调用点+4")
    require(image.code(number(site["continuation"]), 24).hex()
            == site["continuationWindow"].replace(" ", ""),
            "调用后接续窗口 24 字节与合同不符")
    require(struct.unpack("<I", image.code(number(site["target"]), 4))[0]
            == number(site["entryWord"]), "目标入口机器字与合同不符")
    return {"调用点": site["callOffset"], "目标": site["target"],
            "接续窗口字节数": 24, "目标入口": site["entryWord"]}


def run_matrix(obj, contract, states, stocks):
    """对每个状态字与原 getter 返回值执行一次完整流程，逐项核对三段契约。"""
    dart = dart_state()
    call_word = branch26(CALLER, ISLAND, True)
    after = ISLAND + contract["island"]["afterOffset"]
    cases = []
    for state in states:
        for stock in stocks:
            trial = Trial()
            code = materialize(obj, ISLAND, {ORIGINAL_SYMBOL: ORIGINAL_SLOT,
                                             STATE_SYMBOL: STATE_SLOT,
                                             CONTINUATION_SYMBOL: CONTINUATION_SLOT})
            sentinel = sentinel_registers()
            sentinel[15], sentinel[29] = dart["x15"], dart["x29"]
            trial.run(code, call_word, state, stock, dart, sentinel)
            entry = trial.reg_at[GETTER]
            require(entry["gpr"][30] == after,
                    "进入 getter 时 x30 不是岛内接续点：%#x" % entry["gpr"][30])
            require(entry["gpr"][15] == dart["x15"] and entry["gpr"][29] == dart["x29"],
                    "进入 getter 时 Dart 栈或帧指针被改动")
            for index in range(31):
                if index in (16, 17, 30):
                    continue
                require(entry["gpr"][index] == sentinel[index],
                        "进入 getter 前蹦床改动了不该改的寄存器 x%d" % index)
            back = trial.reg_at[CALLER + 4]
            expected = effective_value(state, stock)
            require(back["gpr"][0] == expected,
                    "接续处 x0 不是「状态字在范围内取状态字、否则取原值」：%#x" % back["gpr"][0])
            require(back["gpr"][30] == CALLER + 4,
                    "接续处 x30 未恢复到调用点+4：%#x" % back["gpr"][30])
            for index in range(31):
                # x0 是唯一被允许改写的返回值；x16/x17 是 Dart TMP/TMP2 暂存；
                # x30 由契约单独核对（必须恢复成调用点+4）。
                if index in (0, 16, 17, 30):
                    continue
                require(back["gpr"][index] == sentinel[index],
                        "蹦床改动了不该改的寄存器 x%d" % index)
            for index in range(32):
                require(back["q"][index] == dart["q"][index],
                        "蹦床改动了向量寄存器 Q%d" % index)
            require(back["sp"] == C_STACK + 0x800, "C 栈指针被改动")
            # 「覆盖」只看状态字是否落在 5..20，不能写成 state == expected：
            # 状态字 4 与原返回值 4 会恰好相等，那是原值透传，不是覆盖。
            cases.append({"状态字": state, "原 getter 返回值": stock, "生效上限": expected,
                          "覆盖": 5 <= state <= 20})
    covered = [case for case in cases if case["覆盖"]]
    passthrough = [case for case in cases if not case["覆盖"]]
    require(all(case["生效上限"] == case["状态字"] for case in covered),
            "覆盖用例的生效上限必须等于状态字")
    require(all(case["生效上限"] == case["原 getter 返回值"] for case in passthrough),
            "透传用例必须原样返回 getter 的 x0")
    return cases


def negative_controls(obj, contract, states, stocks):
    """主动破坏蹦床字节，要求流程必须失败，避免只验证脚本自己的期望值。"""
    dart = dart_state()
    call_word = branch26(CALLER, ISLAND, True)
    words = list(struct.unpack("<%dI" % (len(obj.code) // 4), obj.code))
    controls = [
        (0x54000048, NOP, "移除 5..20 范围判定，关闭状态也会覆盖", 0, 3, None),
        (0x2A1103E0, 0x2A1003E0, "把覆盖源写成 w16（已减 5 的中间值）", 20, 3, 20),
        (0xD61F03C0, 0xD63F03C0, "末条 br x30 写成 blr x30，接续处 x30 被改写", 5, 3, 5),
        (0xF940021E, NOP, "不恢复 x30（去掉 ldr x30, [x16]）", 5, 3, 5),
    ]
    probes = []
    for instruction, replacement, label, state, stock, want in controls:
        require(words.count(instruction) == 1,
                "故障注入找不到唯一指令：%s（%d 处）" % (label, words.count(instruction)))
        damaged = list(words)
        damaged[damaged.index(instruction)] = replacement
        payload = type("Patched", (), {
            "code": struct.pack("<%dI" % len(damaged), *damaged),
            "relocations": obj.relocations})()
        try:
            trial = Trial()
            sentinel = sentinel_registers()
            sentinel[15], sentinel[29] = dart["x15"], dart["x29"]
            trial.run(materialize(payload, ISLAND, {ORIGINAL_SYMBOL: ORIGINAL_SLOT,
                                                   STATE_SYMBOL: STATE_SLOT,
                                                   CONTINUATION_SYMBOL: CONTINUATION_SLOT}),
                      call_word, state, stock, dart, sentinel)
        except AuditError:
            probes.append({"对照": label, "已检出": True})
            continue
        back = trial.reg_at.get(CALLER + 4)
        if want is None:
            broken = back is None or back["gpr"][0] != stock
        else:
            broken = (back is None or back["gpr"][0] != want
                      or back["gpr"][30] != CALLER + 4)
        require(broken, "故障对照漏检：%s" % label)
        probes.append({"对照": label, "已检出": True})
    return probes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="固定宿主 libapp.so")
    parser.add_argument("--object", type=Path, required=True, help="交叉汇编的数量点蹦床 .o")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT,
                        help="数量点蹦床合同 JSON")
    parser.add_argument("--require-both-classes", action="store_true",
                        help="要求状态矩阵同时覆盖「覆盖」与「透传」两类用例（真实复核用）")
    args = parser.parse_args()
    try:
        image = ElfImage(args.host_elf.read_bytes())
        contract = json.loads(args.contract.read_text(encoding="utf-8"))
        obj = Arm64Object(args.object.read_bytes(), ISLAND_SYMBOL)
        require(contract.get("notClosedGates"), "合同必须显式保留未关闭门槛")
        require(any("门槛5" in item for item in contract["notClosedGates"]),
                "合同必须显式声明门槛 5 未关闭")
        require(any("门槛3" in item for item in contract["notClosedGates"]),
                "合同必须显式声明门槛 3 未关闭")
        host = verify_host(image, contract)
        island = verify_island(obj, contract)
        states = [number(item) for item in contract["stateMatrix"]]
        stocks = [number(item) for item in contract["stockValues"]]
        if args.require_both_classes:
            require(any(5 <= state <= 20 for state in states)
                    and any(not 5 <= state <= 20 for state in states),
                    "状态矩阵必须同时包含覆盖与透传两类用例")
        cases = run_matrix(obj, contract, states, stocks)
        probes = negative_controls(obj, contract, states, stocks)
        require(len(probes) == contract["counts"]["negativeControls"], "故障对照数量不符")
        require(len(cases) == len(states) * len(stocks), "用例数不符")
        print(json.dumps({"宿主合同": host, "蹦床": island,
                          "行为用例": len(cases), "用例样例": cases[:4],
                          "故障对照": probes, "安装许可": False,
                          "限制": "离线语义试验：getter 用返回值替身（函数体依赖 Dart 运行期"
                                  "对象），只证明蹦床的控制流、寄存器与覆盖契约；不证明安装窗口"
                                  "无并发执行、新代码缓存同步、宿主映射生命周期与写入独占性"},
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, struct.error) as error:
        print("数量点蹦床审计失败：%s" % error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
