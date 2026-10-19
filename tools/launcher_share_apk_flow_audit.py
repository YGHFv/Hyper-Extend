#!/usr/bin/env python3
"""分享 APK 宿主函数体的离线数据流复核；不创建功能实现，不安装、不连接设备。"""

import argparse
import itertools
import json
import struct
from pathlib import Path

from launcher_menu_island_audit import (
    ISLAND_SYMBOL, ORIGINAL_SYMBOL, branch26, materialize, verify_island,
)
from launcher_native_audit import AuditError, ElfImage, digest, direct_branch, number, require
from launcher_share_apk_audit import require_function, symbol_table, verify_image
from launcher_shortcut_count_emulate import Arm64Object


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/test/resources"
DEFAULT_CONTRACT = RESOURCES / "launcher-share-apk-flow-contract.json"
POLICY = "share-apk-host-flow-offline-v1"
ROLES = ("makeBundle", "putFirst", "putSecond", "getApp", "getContext", "getManager",
         "makeRecord", "startActivity")
MATRIX = {"scenarios": ["nonnull", "null", "low32-alias"], "layouts": [0, 1],
          "flags": [0, 7, 15], "modes": ["direct", "island"], "cases": 72,
          "negativeControls": 8, "boundaryAborts": 8}
PAGE = 0x1000
RET = 0xD65F03C0


def load_contracts(path=DEFAULT_CONTRACT):
    return tuple(json.loads(item.read_text(encoding="utf-8")) for item in (
        Path(path), RESOURCES / "launcher-share-apk-pilot-contract.json",
        RESOURCES / "launcher-menu-return-contract.json", RESOURCES / "launcher-menu-island-contract.json"))


def verify_contracts(flow, pilot, returns, island):
    """与已审核的入口/返回值/叶蹦床三份合同交叉核对，不静默替换旧的 24 字节候选。"""
    require(flow["schemaVersion"] == 1 and flow["policy"] == POLICY, "函数体合同版本或策略不符")
    require(flow["installPermitted"] is False and flow["notClosedGates"] == [1, 3, 5],
            "函数体试验不得放行安装或关闭门槛")
    require(pilot["policy"] == "share-apk-entry-pilot-offline-v1"
            and returns["policy"] == "menu-call-site-return-contract-offline-v1"
            and island["policy"] == "menu-call-site-island-offline-v1", "依赖合同策略不符")
    for basis in (pilot, returns, island):
        require(basis["installPermitted"] is False and basis["image"] == flow["image"],
                "依赖合同的安装许可或宿主身份不一致")
    require(pilot["pilot"]["installPermitted"] is False, "入口候选不得声明安装许可")
    function = flow["function"]
    require({key: function[key] for key in ("name", "address", "size", "sha256")} == pilot["pilot"]["target"],
            "函数体与既有入口候选不一致")
    raw = bytes.fromhex(function["bytes"])
    require(len(raw) == function["size"] == 188 and digest(raw) == function["sha256"], "函数体字节或摘要不符")
    expected_sites = [site for site in returns["sites"] if site["feature"] == "分享 APK"]
    require(len(flow["sites"]) == len(expected_sites) == 2, "分享调用点必须恰好两个")
    for site, basis in zip(flow["sites"], expected_sites):
        require(site == {"address": basis["offset"], "word": basis["word"],
                         "continuationWord": basis["continuationWord"]}
                and basis["target"] == function["address"]
                and basis["returnUse"] == "丢弃" and basis["rewriteReturn"] is False,
                "分享调用点或返回值用法与既有合同不符")
    leaf_sites = [site for site in island["sites"] if site["feature"] == "分享 APK"]
    require([(site["callOffset"], site["word"], site["target"]) for site in leaf_sites]
            == [(site["address"], site["word"], function["address"]) for site in flow["sites"]],
            "分享调用点与叶蹦床合同不一致")
    require([call["role"] for call in flow["calls"]] == list(ROLES), "外部调用角色集合或顺序不符")
    require([{"address": call["address"], "target": call["target"], "name": call["symbol"]}
             for call in flow["calls"] if call["symbol"] is not None]
            == pilot["pilot"]["synchronousEvidence"]["callees"], "已命名外部边界与入口合同不符")
    require([call for call in flow["calls"] if call["symbol"] is None] == [
        {"address": "0x17fda8c", "target": "0x9a2c58", "role": "makeBundle", "symbol": None},
        {"address": "0x17fdae0", "target": "0x18c00d8", "role": "makeRecord", "symbol": None}],
        "未命名外部边界必须保留为模型角色")
    require(flow["matrix"] == MATRIX, "行为矩阵不得缩减或更换")
    require(flow["poolSlots"] == {"firstKey": "0xb9458", "secondKey": "0xb9460",
                                 "firstField": "0xb9468", "lastField": "0xb9470"},
            "对象池槽偏移不符")
    require(flow["recordWrites"] == [
        {"address": "0x17fdaf0", "offset": "0x7", "size": 4, "value": "firstField"},
        {"address": "0x17fdaf8", "offset": "0xf", "size": 8, "value": "one"},
        {"address": "0x17fdb00", "offset": "0x1b", "size": 4, "value": "bundle"},
        {"address": "0x17fdb0c", "offset": "0x27", "size": 4, "value": "lastField"}],
        "对象字段写入合同不符")
    require(flow["nullBranch"] == {
        "compare": "0x17fdac0", "compareWord": "0x6b16001f", "address": "0x17fdac4",
        "word": "0x540002c0", "target": "0x17fdb1c", "comparisonBits": 32}, "空接口分支合同不符")


def verify_host(image, flow):
    verify_image(image, flow)
    table = symbol_table(image)
    require_function(image, table, flow["function"], "分享函数体")
    start = number(flow["function"]["address"])
    raw = image.code(start, flow["function"]["size"])
    require(raw.hex() == flow["function"]["bytes"], "宿主函数体字节与合同不符")
    calls = [(start + index * 4, edge[1]) for index, (word,) in enumerate(struct.iter_unpack("<I", raw))
             if (edge := direct_branch(word, start + index * 4)) and edge[0] == "BL"]
    require(calls == [(number(call["address"]), number(call["target"])) for call in flow["calls"]],
            "函数内完整 BL 集合与外部边界不符")
    for call in flow["calls"]:
        if call["symbol"] is not None:
            matches = table.get(call["symbol"], [])
            require(len(matches) == 1 and matches[0]["address"] == number(call["target"]),
                    "外部调用符号身份不符：" + call["role"])
    for site in flow["sites"]:
        address = number(site["address"])
        require(image.code(address, 8) == struct.pack("<II", number(site["word"]), number(site["continuationWord"])),
                "调用点或接续机器字漂移")
        require(direct_branch(number(site["word"]), address) == ("BL", start), "调用点未指向分享入口")
    return raw


def verify_isolation():
    text = (ROOT / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
    require(not any("launcher_menu_island_arm64.S" in line
                    for line in text.splitlines() if not line.lstrip().startswith("#")),
            "叶蹦床不得进入产品构建目标")


class FlowTrial:
    """执行真实函数体；七个外部地址的八次调用都由明确的边界替身提供结果。"""

    def __init__(self, image, flow, obj, site, mode, scenario, layout=0, flags=0,
                 body=None, island_code=None, call_word=None, abort_at=None):
        try:
            import unicorn as uc
            from unicorn import arm64_const as reg
        except ImportError as error:
            raise AuditError("缺少本地仿真依赖；请使用已有 launcher 仿真虚拟环境") from error
        require(mode in MATRIX["modes"] and scenario in MATRIX["scenarios"]
                and layout in MATRIX["layouts"] and 0 <= flags < 16, "未知的函数体试验场景")
        self.uc, self.reg, self.flow = uc, reg, flow
        self.m = uc.Uc(uc.UC_ARCH_ARM64, uc.UC_MODE_ARM)
        self.gpr = [getattr(reg, "UC_ARM64_REG_X%d" % index) for index in range(31)]
        self.qreg = [getattr(reg, "UC_ARM64_REG_Q%d" % index) for index in range(32)]
        self.bias = layout * 0x1000000000
        self.start = self.bias + number(flow["function"]["address"])
        self.caller = self.bias + number(site["address"])
        self.island, self.slot = self.bias + 0x2000000, self.bias + 0x2400000
        self.top = 0x300000000 + layout * 0x100000 + 0x800
        self.c_top, self.pool = self.top + 0x10000, 0x310000000 + layout * 0x100000
        self.heap = 0x320000000 + layout * 0x100000
        self.tokens = {key: self.heap + offset for key, offset in {
            "null": 0x1, "arg1": 0x81, "arg2": 0x101, "bundle": 0x181, "app": 0x281,
            "context": 0x301, "manager": 0x381, "record": 0x401, "firstKey": 0x501,
            "secondKey": 0x581, "firstField": 0x601, "lastField": 0x681}.items()}
        self.tokens["one"] = 1
        self.record = self.tokens["record"]
        self.scenario, self.abort_at = scenario, abort_at
        self.calls, self.writes, self.failure, self.result = [], [], None, None
        self.entered = False
        self.expected_calls = flow["calls"] if scenario == "nonnull" else flow["calls"][:4]
        code_pages = {self.caller & -PAGE, self.start & -PAGE, self.island & -PAGE}
        code_pages.update((self.bias + number(call["target"])) & -PAGE for call in flow["calls"])
        for page in sorted(code_pages):
            self.m.mem_map(page, PAGE)
        for page in (self.slot, self.top & -PAGE, self.c_top & -PAGE,
                     (self.pool + number(flow["poolSlots"]["firstKey"])) & -PAGE, self.heap):
            self.m.mem_map(page, PAGE, uc.UC_PROT_READ | uc.UC_PROT_WRITE)
        payload = image.code(number(flow["function"]["address"]), flow["function"]["size"]) if body is None else body
        require(len(payload) == flow["function"]["size"], "试验函数体长度不符")
        self.m.mem_write(self.start, payload)
        for call in flow["calls"]:
            self.m.mem_write(self.bias + number(call["target"]), struct.pack("<I", RET))
        emitted = materialize(obj, self.island, {ORIGINAL_SYMBOL: self.slot}) if island_code is None else island_code
        self.m.mem_write(self.island, emitted)
        self.m.mem_write(self.slot, struct.pack("<Q", self.start))
        word = number(site["word"]) if mode == "direct" else branch26(self.caller, self.island, True)
        self.m.mem_write(self.caller, struct.pack("<I", word if call_word is None else call_word))
        self.m.mem_write(self.caller + 4, image.code(number(site["address"]) + 4, 4))
        self.m.mem_write(self.record, b"\xa5" * 64)
        self.m.mem_write(self.top - 64, b"\x5a" * 64)
        for name, offset in flow["poolSlots"].items():
            self.m.mem_write(self.pool + number(offset), struct.pack("<Q", self.tokens[name]))
        for page in code_pages:
            self.m.mem_protect(page, PAGE, uc.UC_PROT_READ | uc.UC_PROT_EXEC)
        for page in (self.slot, (self.pool + number(flow["poolSlots"]["firstKey"])) & -PAGE):
            self.m.mem_protect(page, PAGE, uc.UC_PROT_READ)
        self.initial = [0x1BAD000000000000 | (index << 16) | layout for index in range(31)]
        self.initial[1], self.initial[2] = self.tokens["arg1"], self.tokens["arg2"]
        self.initial[15], self.initial[22], self.initial[27] = self.top, self.tokens["null"], self.pool
        self.initial[29], self.initial[30] = self.top + 0x100, self.bias + 0x2600000
        self.initial_q = [(0xABCD << 96) | index | (layout << 32) for index in range(32)]
        for register, value in zip(self.gpr, self.initial):
            self.m.reg_write(register, value)
        for register, value in zip(self.qreg, self.initial_q):
            self.m.reg_write(register, value)
        self.m.reg_write(reg.UC_ARM64_REG_SP, self.c_top)
        self.m.reg_write(reg.UC_ARM64_REG_NZCV, flags << 28)
        self.initial_flags = flags << 28
        self.m.hook_add(uc.UC_HOOK_CODE, self._on_code)
        self.m.hook_add(uc.UC_HOOK_MEM_WRITE, self._on_write)

    def read_x(self, index):
        return self.m.reg_read(self.gpr[index])

    def expected_record(self):
        data = bytearray(b"\xa5" * 64)
        for write in self.flow["recordWrites"]:
            width, offset = write["size"], number(write["offset"])
            value = self.tokens[write["value"]] & ((1 << (width * 8)) - 1)
            data[offset:offset + width] = value.to_bytes(width, "little")
        return bytes(data)

    def _on_write(self, machine, access, address, size, value, context):
        try:
            pc = machine.reg_read(self.reg.UC_ARM64_REG_PC)
            if self.top - 32 <= address and address + size <= self.top:
                return
            candidates = [write for write in self.flow["recordWrites"]
                          if pc == self.bias + number(write["address"])]
            require(len(candidates) == 1 and self.scenario == "nonnull", "函数体出现未审核内存写入")
            write = candidates[0]
            expected = self.tokens[write["value"]] & ((1 << (size * 8)) - 1)
            require(address == self.record + number(write["offset"]) and size == write["size"]
                    and value & ((1 << (size * 8)) - 1) == expected, "对象字段写入地址、宽度或值不符")
            self.writes.append((number(write["address"]), address - self.record, size))
        except AuditError as error:
            self.failure = str(error)
            machine.emu_stop()

    def _check_entry(self):
        require(not self.entered, "分享函数被重复执行")
        for index in range(31):
            if index in (16, 17):
                continue
            wanted = self.caller + 4 if index == 30 else self.initial[index]
            require(self.read_x(index) == wanted, "进入宿主函数时寄存器不符：x%d" % index)
        require(self.m.reg_read(self.reg.UC_ARM64_REG_SP) == self.c_top, "入口 C 栈被修改")
        require(self.m.reg_read(self.reg.UC_ARM64_REG_NZCV) == self.initial_flags, "叶蹦床破坏条件标志")
        require([self.m.reg_read(register) for register in self.qreg] == self.initial_q, "叶蹦床破坏向量寄存器")
        self.entered = True

    def _boundary(self, address):
        index = len(self.calls)
        require(index < len(self.expected_calls), "空接口分支仍进入了后续外部调用")
        call = self.expected_calls[index]
        require(address == self.bias + number(call["target"])
                and self.read_x(30) == self.bias + number(call["address"]) + 4,
                "外部调用次序、目标或返回地址不符")
        role = call["role"]
        expected = {
            "makeBundle": {0: "arg1", 1: "null", 3: "arg2"},
            "putFirst": {1: "bundle", 2: "firstKey", 3: "arg2"},
            "putSecond": {1: "bundle", 2: "secondKey", 3: "arg2"},
            "getApp": {},
            "getContext": {1: "app", 2: "bundle"},
            "getManager": {1: "context"},
            "makeRecord": {},
            "startActivity": {1: "manager", 2: "record"},
        }[role]
        require(all(self.read_x(register) == self.tokens[name] for register, name in expected.items()),
                "外部边界参数不符：" + role)
        if role == "startActivity":
            require(bytes(self.m.mem_read(self.record, 64)) == self.expected_record(),
                    "启动边界的对象字段或邻接字节不符")
        self.calls.append(role)
        require(self.abort_at != role, "外部边界主动中止：" + role)
        result_name = {"makeBundle": "bundle", "getApp": "app", "getContext": "context",
                       "getManager": "manager", "makeRecord": "record"}.get(role)
        result = self.tokens[result_name] if result_name else 0xBAD00000 + index
        if role == "getApp" and self.scenario != "nonnull":
            result = self.tokens["null"] + ((1 << 32) if self.scenario == "low32-alias" else 0)
        # 故意扰动调用者临时寄存器，防止用“不破坏任何寄存器”的替身掩盖帧槽恢复错误。
        for register in (*range(15), 16, 17):
            self.m.reg_write(self.gpr[register], 0xEE0000000000 | (index << 16) | register)
        for register in range(8):
            self.m.reg_write(self.qreg[register], (0xCC << 120) | (index << 16) | register)
        self.m.reg_write(self.reg.UC_ARM64_REG_NZCV, ((index * 3) & 15) << 28)
        self.m.reg_write(self.gpr[0], result)

    def _finish(self):
        require(self.entered and self.calls == [call["role"] for call in self.expected_calls],
                "函数没有完成预期外部调用，空接口分支结果不符")
        require(self.read_x(0) == self.tokens["null"], "函数未返回 Dart null")
        require(self.read_x(15) == self.top and self.read_x(29) == self.initial[29]
                and self.read_x(30) == self.caller + 4, "Dart 栈、帧或返回地址未恢复")
        require(self.m.reg_read(self.reg.UC_ARM64_REG_SP) == self.c_top, "函数改动 C 栈")
        require(all(self.read_x(index) == self.initial[index] for index in range(18, 29)),
                "函数改动模型要求保留的 Dart 活寄存器")
        require([self.m.reg_read(register) for register in self.qreg[8:]] == self.initial_q[8:],
                "函数改动模型要求保留的向量寄存器")
        wanted_writes = [(number(write["address"]), number(write["offset"]), write["size"])
                         for write in self.flow["recordWrites"]] if self.scenario == "nonnull" else []
        require(self.writes == wanted_writes, "对象字段写入顺序或次数不符")
        expected_record = self.expected_record() if self.scenario == "nonnull" else b"\xa5" * 64
        require(bytes(self.m.mem_read(self.record, 64)) == expected_record, "记录对象出现意外改写")
        self.result = {
            "calls": list(self.calls), "writes": list(self.writes),
            "gpr": [self.read_x(index) for index in range(31) if index not in (16, 17)],
            "q": [self.m.reg_read(register) for register in self.qreg],
            "sp": self.m.reg_read(self.reg.UC_ARM64_REG_SP),
            "nzcv": self.m.reg_read(self.reg.UC_ARM64_REG_NZCV),
            "stack": bytes(self.m.mem_read(self.top - 64, 64)).hex(),
            "record": bytes(self.m.mem_read(self.record, 64)).hex(),
        }
        self.m.emu_stop()

    def _on_code(self, machine, address, size, context):
        try:
            if address == self.start:
                self._check_entry()
            if address == self.caller + 4:
                self._finish()
                return
            if address in {self.bias + number(call["target"]) for call in self.flow["calls"]}:
                self._boundary(address)
            elif self.start <= address < self.start + self.flow["function"]["size"]:
                require(self.entered, "未从宿主函数入口进入")
            else:
                require(address == self.caller or self.island <= address < self.island + 16,
                        "执行流离开已审核代码范围")
        except AuditError as error:
            self.failure = str(error)
            machine.emu_stop()

    def run(self):
        try:
            self.m.emu_start(self.caller, 0, count=512)
        except self.uc.UcError as error:
            raise AuditError("函数体执行被隔离模拟器拒绝") from error
        require(self.failure is None, self.failure or "")
        require(self.result is not None, "函数体未在指令预算内回到调用点接续")
        return self.result


def run_matrix(image, flow, obj):
    comparisons = 0
    for site, scenario, layout, flags in itertools.product(flow["sites"], MATRIX["scenarios"],
                                                         MATRIX["layouts"], MATRIX["flags"]):
        direct = FlowTrial(image, flow, obj, site, "direct", scenario, layout, flags).run()
        indirect = FlowTrial(image, flow, obj, site, "island", scenario, layout, flags).run()
        require(direct == indirect, "直接调用与叶蹦床调用产生不同的数据流结果")
        comparisons += 1
    require(comparisons * 2 == flow["matrix"]["cases"], "行为矩阵用例数不符")
    return {"执行次数": comparisons * 2, "逐项等价对照": comparisons,
            "非空接口外部调用数": 8, "空接口外部调用数": 4, "非空接口对象字段写入数": 4}


def negative_controls(image, flow, obj):
    site = flow["sites"][0]
    raw = image.code(number(flow["function"]["address"]), flow["function"]["size"])
    controls = []

    def rejected(label, why, **kwargs):
        try:
            FlowTrial(image, flow, obj, site, "island", "nonnull", **kwargs).run()
        except AuditError as error:
            require(why in str(error), "故障对照在非预期位置失败：" + label + "：" + str(error))
            controls.append({"对照": label, "已拒绝": True})
        else:
            raise AuditError("故障对照被错误放行：" + label)

    for label, address, word, why in (
        ("入参帧槽恢复改错", 0x17FDA94, 0xAA0103E3, "边界参数不符"),
        ("第二个对象池键重复使用第一个", 0x17FDAB4, 0xF9422C42, "边界参数不符"),
        ("反转空接口分支", 0x17FDAC4, 0x540002C1, "空接口分支结果不符"),
        ("对象首字段误写成 64 位", 0x17FDAF0, 0xF8007020, "字段写入"),
        ("对象整数槽由 1 改成 2", 0x17FDAF4, 0xD2800040, "字段写入"),
        ("把 Dart null 错写成整数零", 0x17FDB1C, 0xAA1F03E0, "未返回 Dart null"),
    ):
        changed = bytearray(raw)
        struct.pack_into("<I", changed, address - number(flow["function"]["address"]), word)
        rejected(label, why, body=bytes(changed))
    emitted = bytearray(materialize(obj, 0x2000000, {ORIGINAL_SYMBOL: 0x2400000}))
    struct.pack_into("<I", emitted, 12, 0xD63F0200)
    rejected("叶蹦床尾跳误写成 BLR", "寄存器不符：x30", island_code=bytes(emitted))
    rejected("调用点误写成 B", "寄存器不符：x30",
             call_word=branch26(number(site["address"]), 0x2000000, False))
    require(len(controls) == flow["matrix"]["negativeControls"], "故障对照数不符")
    return controls


def boundary_aborts(image, flow, obj):
    results = []
    for role in ROLES:
        try:
            FlowTrial(image, flow, obj, flow["sites"][0], "island", "nonnull", abort_at=role).run()
        except AuditError as error:
            require(str(error) == "外部边界主动中止：" + role, "边界中止对照在非预期位置失败")
            results.append(role)
        else:
            raise AuditError("外部边界中止被误报为正常返回")
    return {"已拒绝正常完成判定": len(results), "边界": results,
            "限制": "仅验证试验中止不会报成功，不模拟 Dart 异常展开或真实失败回退"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True, help="本机已留存的固定宿主 ELF")
    parser.add_argument("--object", type=Path, required=True, help="已交叉汇编的 16 字节叶蹦床对象")
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args(argv)
    try:
        flow, pilot, returns, island = load_contracts(args.contract)
        verify_contracts(flow, pilot, returns, island)
        verify_isolation()
        image = ElfImage(args.host_elf.read_bytes())
        verify_host(image, flow)
        obj = Arm64Object(args.object.read_bytes(), ISLAND_SYMBOL)
        verify_island(obj, island)
        matrix = run_matrix(image, flow, obj)
        controls = negative_controls(image, flow, obj)
        aborts = boundary_aborts(image, flow, obj)
        print(json.dumps({"策略": POLICY, "宿主函数字节": flow["function"]["size"],
                          "矩阵": matrix, "故障对照": controls, "边界中止对照": aborts,
                          "安装许可": False, "未关闭门槛": [1, 3, 5],
                          "限制": flow["verificationBoundary"], "未解决": flow["unresolved"]},
                         ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError, struct.error) as error:
        print("分享 APK 函数体复核失败：" + (type(error).__name__ if isinstance(error, OSError) else str(error)))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
