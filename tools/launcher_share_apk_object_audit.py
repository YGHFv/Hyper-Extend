#!/usr/bin/env python3
"""分享对象的只读指令取证：辨认分配桩和字段类型，不猜池槽内容，不安装。"""

import argparse
import itertools
import json
import struct
from pathlib import Path

import launcher_share_apk_flow_audit as flow_audit
from launcher_menu_island_audit import ISLAND_SYMBOL, verify_island
from launcher_native_audit import AuditError, ElfImage, digest, direct_branch, number, require
from launcher_share_apk_audit import symbol_table, verify_image
from launcher_shortcut_count_emulate import Arm64Object


DEFAULT_CONTRACT = flow_audit.RESOURCES / "launcher-share-apk-object-contract.json"
POLICY = "share-apk-object-boundaries-offline-v1"
MATRIX = {"apiStates": ["ready", "lazy"], "cases": 144, "comparisons": 72, "rejections": 8}
FUNCTIONS = {
    "bundleWrapper": (0x9A2C58, 108, None),
    "bundleNew": (0x9A2CC4, 216, "RustLibApiImpl.hyperApiBundleBundleNew"),
    "intentAllocate": (0x18C00D8, 12, None),
    "allocateObject": (0x1B57110, 76, None),
    "intentEquals": (0x1798210, 468, "Intent.=="),
    "intentEncode": (0x8CBF64, 240, "RustLibApiImpl.sse_encode_intent"),
    "intentDecode": (0x8CCAF0, 232, "RustLibApiImpl.sse_decode_intent"),
    "intentDcoDecode": (0x9650E0, 836, "RustLibApiImpl.dco_decode_intent"),
}
FIELD_SHAPES = [(7, 4, "optionalString"), (11, 4, "optionalUri"), (15, 8, "int32Wire"),
                (23, 4, "optionalStringList"), (27, 4, "optionalBundle"),
                (31, 4, "optionalComponentName"), (35, 4, "optionalString"),
                (39, 4, "optionalString")]
OPAQUE = "opt_box_autoadd_Auto_Owned_RustOpaque_flutter_rust_bridgefor_generatedRustAutoOpaqueInner"
CODECS = ["opt_String", OPAQUE + "Uri", None, "opt_list_String", OPAQUE + "Bundle",
          "opt_box_autoadd_component_name", "opt_String", "opt_String"]
TAG = 0xD7831C
LAZY, ERROR_ALLOCATE, ERROR_RAISE = 0x1B56370, 0x18A72A8, 0x1B564B0
PAGE = flow_audit.PAGE


def load_contract(path=DEFAULT_CONTRACT):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def word_at(image, address):
    return struct.unpack("<I", image.code(address, 4))[0]


def ldur_stur(word):
    """仅解码本合同使用的 W/X LDUR/STUR，偏移相对 tagged 对象句柄。"""
    forms = {0xB8400000: ("load", 4), 0xF8400000: ("load", 8),
             0xB8000000: ("store", 4), 0xF8000000: ("store", 8)}
    shape = forms.get(word & 0xFFC00000)
    require(shape is not None, "字段访问不是已审核的 LDUR/STUR")
    offset = (word >> 12) & 0x1FF
    return (*shape, (word >> 5) & 31, word & 31, offset - 512 if offset & 256 else offset)


def verify_contract(contract, flow):
    require(contract["schemaVersion"] == 1 and contract["policy"] == POLICY, "对象合同版本或策略不符")
    require(contract["installPermitted"] is False and contract["notClosedGates"] == [1, 3, 5],
            "对象试验不得放行安装或关闭门槛")
    require(contract["image"] == flow["image"], "对象合同与函数体合同宿主身份不一致")
    require(contract["poolContentsResolved"] is False and contract["poolSlots"] == flow["poolSlots"],
            "池槽内容尚未解码，不得声明已解析或替换槽位")
    require(contract["matrix"] == MATRIX, "对象行为矩阵不得缩减")
    require(number(contract["intentClassId"]) == 0xD78 and number(contract["allocationTag"]) == TAG
            and contract["allocationSize"] == 48, "Intent 类型或分配布局不符")
    require(set(contract["functions"]) == set(FUNCTIONS), "函数证据集合不完整")
    for role, (address, size, symbol) in FUNCTIONS.items():
        item = contract["functions"][role]
        require((number(item["address"]), item["size"], item["symbol"]) == (address, size, symbol),
                "函数证据范围或命名身份不符：" + role)
        raw = bytes.fromhex(item["bytes"])
        require(len(raw) == size and digest(raw) == item["sha256"], "函数证据字节摘要不符")
    require(len(contract["fields"]) == 8, "Intent 必须保留全部八个字段")
    for index, (field, shape) in enumerate(zip(contract["fields"], FIELD_SHAPES)):
        require((number(field["offset"]), field["memoryWidth"], field["kind"]) == shape
                and field["index"] == index and field["semanticName"] is None,
                "字段只能声明已证偏移、宽度和类型，不能猜语义名")
        for side in ("encode", "decode"):
            expected = ("WriteBuffer.putInt32" if side == "encode" else "ReadBuffer.getInt32") if index == 2 \
                else "RustLibApiImpl.sse_" + side + "_" + CODECS[index]
            require(field[side]["symbol"] == expected, "编解码类型与字段合同不符")


def verify_host(image, contract, flow):
    verify_image(image, contract)
    table = symbol_table(image)
    for item in contract["functions"].values():
        address = number(item["address"])
        raw = image.code(address, item["size"])
        require(raw.hex() == item["bytes"] and digest(raw) == item["sha256"], "对象函数机器码漂移")
        if item["symbol"] is not None:
            # sse_decode_intent 有同名包装器；用名字、首地址和长度三者确认，不借最近符号猜归属。
            matches = [value for value in table.get(item["symbol"], []) if value["address"] == address]
            require(len(matches) == 1 and matches[0]["size"] == item["size"], "对象函数符号身份不符")
    def edge(address, target):
        require(direct_branch(word_at(image, address), address) == ("BL", target), "对象边界 BL 不符")

    edge(number(flow["calls"][0]["address"]), FUNCTIONS["bundleWrapper"][0])
    edge(number(flow["calls"][6]["address"]), FUNCTIONS["intentAllocate"][0])
    for address, target in ((0x9A2C78, LAZY), (0x9A2C98, 0x9A2CC4),
                            (0x9A2CA8, ERROR_ALLOCATE), (0x9A2CBC, ERROR_RAISE),
                            (0x8CCB88, 0x18C00D8), (0x965320, 0x18C00D8)):
        edge(address, target)
    words = struct.unpack("<III", image.code(0x18C00D8, 12))
    require(words[0] & 0xFFE0001F == 0xD2800002 and words[1] & 0xFFE0001F == 0xF2A00002,
            "分配桩不是两条向 x2 装入 tag 的宽立即数指令")
    tag = ((words[0] >> 5) & 0xFFFF) | (((words[1] >> 5) & 0xFFFF) << 16)
    require(tag == number(contract["allocationTag"])
            and direct_branch(words[2], 0x18C00E0) == ("B", 0x1B57110), "分配 tag 或尾跳不符")
    require(word_at(image, 0x1798258) == 0xD34C7C42
            and word_at(image, 0x179825C) == 0xF135E05F
            and (tag >> 12) & 0xFFFFF == number(contract["intentClassId"]), "分配类 ID 与 Intent.== 不一致")
    require(image.code(0x1B57110, 8) == struct.pack("<II", 0xD3482C44, 0xD37CEC84)
            and ((tag >> 8) & 15) * 16 == contract["allocationSize"], "真实分配长度提取不符")
    for index, field in enumerate(contract["fields"]):
        offset, width = number(field["offset"]), field["memoryWidth"]
        load = ldur_stur(word_at(image, number(field["encode"]["load"])))
        store = ldur_stur(word_at(image, number(field["decode"]["store"])))
        require(load == ("load", width, 4 if index == 0 else 0, 2, offset)
                and store == ("store", width, 0, 1, offset), "编解码字段寄存器、偏移或宽度不一致")
        for side in ("encode", "decode"):
            item = field[side]
            edge(number(item["call"]), number(item["target"]))
            matches = [value for value in table.get(item["symbol"], [])
                       if value["address"] == number(item["target"])]
            require(len(matches) == 1, "字段编解码目标符号不符")
    for role, side in (("intentEncode", "encode"), ("intentDecode", "decode")):
        start, size, _ = FUNCTIONS[role]
        actual = [(address, branch[1]) for address in range(start, start + size, 4)
                  if (branch := direct_branch(word_at(image, address), address)) and branch[0] == "BL"]
        expected = [(number(field[side]["call"]), number(field[side]["target"])) for field in contract["fields"]]
        if side == "decode":
            expected.append((0x8CCB88, 0x18C00D8))
        require(actual == expected, "编解码函数完整 BL 集合不符")
    for write in flow["recordWrites"]:
        matched = [field for field in contract["fields"] if number(field["offset"]) == number(write["offset"])]
        require(len(matched) == 1 and matched[0]["memoryWidth"] == write["size"], "分享字段与 Intent 布局不一致")
    return {"类ID": hex(tag >> 12), "分配字节": contract["allocationSize"], "字段数": 8,
            "整数槽": "内存 8 字节，命名编解码边界为 Int32；不据此命名为 flags",
            "池槽内容已解码": False}


class ObjectTrial(flow_audit.FlowTrial):
    """沿用函数体判定，改为执行真实 Bundle 包装器、Intent 桩和通用分配快路径。"""

    def __init__(self, image, flow, obj, site, mode, scenario, layout=0, flags=0,
                 api_state="ready", space="room", patches=None):
        require(api_state in ("ready", "lazy", "missing") and space in ("room", "exact", "short"),
                "未知对象边界试验场景")
        super().__init__(image, flow, obj, site, mode, scenario, layout, flags)
        self.api_state, self.space = api_state, space
        self.thread, self.static_table, self.runtime_objects = self.heap + 0x2000, self.heap + 0x4000, self.heap + 0x8000
        self.root, self.bridge, self.api = self.runtime_objects + 1, self.runtime_objects + 0x81, self.runtime_objects + 0x101
        self.sentinel, self.metadata = self.runtime_objects + 0x201, self.runtime_objects + 0x281
        self.slow = self.bias + 0x2700000
        self.raw_record = self.record - 1
        self.allocating = False
        self.allocation_writes, self.nested, self.lazy_count = [], [], 0
        for register, value in ((26, self.thread), (28, self.heap >> 32)):
            self.initial[register] = value
            self.m.reg_write(self.gpr[register], value)
        for page in (self.thread, self.static_table + 0x2000, self.runtime_objects, self.pool):
            self.m.mem_map(page, PAGE, self.uc.UC_PROT_READ | self.uc.UC_PROT_WRITE)
        def put(address, value, width=8):
            self.m.mem_write(address, (value & ((1 << (width * 8)) - 1)).to_bytes(width, "little"))
        put(self.thread + 0x78, self.static_table)
        put(self.static_table + 0x23C0, self.sentinel if api_state == "lazy" else self.root)
        put(self.root + 11, self.tokens["null"] if api_state == "missing" else self.bridge, 4)
        put(self.bridge + 19, self.api, 4)
        put(self.pool + 0x40, self.sentinel)
        put(self.pool + 0x2D0, self.metadata)
        put(self.thread + 0x60, self.raw_record)
        put(self.thread + 0x68, self.raw_record + {"room": 0x100, "exact": 48, "short": 32}[space])
        put(self.thread + 0x230, self.slow)
        for page in (self.static_table + 0x2000, self.runtime_objects, self.pool):
            self.m.mem_protect(page, PAGE, self.uc.UC_PROT_READ)
        code = {address: image.code(address, size) for address, size, _ in
                (FUNCTIONS[role] for role in ("bundleWrapper", "intentAllocate", "allocateObject"))}
        code.update({address: struct.pack("<I", flow_audit.RET)
                     for address in (LAZY, ERROR_ALLOCATE, 0x9A2CC4)})
        code[self.slow - self.bias] = struct.pack("<I", flow_audit.RET)
        for address, payload in code.items():
            absolute = self.bias + address
            page = absolute & -PAGE
            if not any(start <= page <= end for start, end, _ in self.m.mem_regions()):
                self.m.mem_map(page, PAGE)
            changed = bytearray(payload)
            for at, word in (patches or {}).items():
                if address <= at < address + len(changed):
                    struct.pack_into("<I", changed, at - address, word)
            self.m.mem_write(absolute, bytes(changed))
            self.m.mem_protect(page, PAGE, self.uc.UC_PROT_READ | self.uc.UC_PROT_EXEC)

    def _clobber(self, result):
        for register in (*range(15), 16, 17):
            self.m.reg_write(self.gpr[register], 0xEE1100000000 | register)
        for register in range(8):
            self.m.reg_write(self.qreg[register], (0xCD << 120) | register)
        self.m.reg_write(self.reg.UC_ARM64_REG_NZCV, 0xF0000000)
        self.m.reg_write(self.gpr[0], result)

    def _boundary(self, address):
        if address - self.bias not in (0x9A2C58, 0x18C00D8):
            return super()._boundary(address)
        index = len(self.calls)
        require(index < len(self.expected_calls), "对象调用越出预期分支")
        call = self.expected_calls[index]
        require(address == self.bias + number(call["target"])
                and self.read_x(30) == self.bias + number(call["address"]) + 4, "对象调用入口或链接地址不符")
        if call["role"] == "makeBundle":
            require(self.read_x(0) == self.tokens["arg1"] and self.read_x(1) == self.tokens["null"]
                    and self.read_x(3) == self.tokens["arg2"], "Bundle 包装器入口参数不符")
        else:
            require(call["role"] == "makeRecord" and not self.allocating, "分配桩重复进入")
            self.allocating = True
        self.calls.append(call["role"])

    def expected_record(self):
        # 快路径从原始对象 +8 起作三次 STP，最后一次多初始化分配末尾之后的 8 字节。
        data = bytearray(TAG.to_bytes(8, "little")[1:] + struct.pack("<I", self.tokens["null"] & 0xFFFFFFFF) * 12
                         + b"\xa5" * 9)
        for write in self.flow["recordWrites"]:
            width, offset = write["size"], number(write["offset"])
            data[offset:offset + width] = (self.tokens[write["value"]] & ((1 << (width * 8)) - 1)).to_bytes(width, "little")
        return bytes(data)

    def allocation_expectations(self):
        null = self.tokens["null"] & 0xFFFFFFFF
        return [(0x1B57128, self.thread + 0x60, 8, self.raw_record + 48),
                (0x1B5712C, self.raw_record, 8, TAG)] + [
                    (0x1B5713C, self.raw_record + offset, 8, null | (null << 32))
                    for offset in range(8, 56, 8)]

    def _on_write(self, machine, access, address, size, value, context):
        pc = machine.reg_read(self.reg.UC_ARM64_REG_PC) - self.bias
        try:
            if pc == 0x9A2C58 and self.top - 48 <= address and address + size <= self.top - 32:
                return
            if 0x1B57110 <= pc < 0x1B5715C:
                require(self.allocating, "未进入分配桩就写堆")
                actual = (pc, address, size, value & ((1 << (size * 8)) - 1))
                expected = self.allocation_expectations()
                require(len(self.allocation_writes) < len(expected)
                        and actual == expected[len(self.allocation_writes)], "分配快路径写入地址、宽度、tag 或初值不符")
                self.allocation_writes.append(actual)
                return
            super()._on_write(machine, access, address, size, value, context)
        except AuditError as error:
            self.failure = str(error)
            machine.emu_stop()

    def _on_code(self, machine, address, size, context):
        relative = address - self.bias
        try:
            if relative == LAZY:
                require(self.api_state == "lazy" and self.lazy_count == 0
                        and self.read_x(30) == self.bias + 0x9A2C7C
                        and self.read_x(2) == self.metadata, "迟初始化参数或调用次数不符")
                self.lazy_count += 1
                self._clobber(self.root)
                return
            if relative == 0x9A2CC4:
                require(self.calls == ["makeBundle"] and not self.nested
                        and self.read_x(30) == self.bias + 0x9A2C9C and self.read_x(1) == self.api,
                        "Bundle API 参数或转发边界不符")
                self.nested.append("RustLibApiImpl.hyperApiBundleBundleNew")
                self._clobber(self.tokens["bundle"])
                return
            if relative == ERROR_ALLOCATE:
                require(self.read_x(30) == self.bias + 0x9A2CAC, "异常构造边界来源不符")
                raise AuditError("Bundle 运行时接口缺失，异常构造路径未建模")
            if address == self.slow:
                require(self.read_x(2) == TAG and self.read_x(1) == self.tokens["null"],
                        "分配慢路径参数不符")
                raise AuditError("分配慢路径未建模，不报告成功")
            if 0x9A2C58 < relative < 0x9A2CC4:
                require(self.calls == ["makeBundle"], "Bundle 包装器未从预期入口进入")
                return
            if 0x18C00D8 < relative < 0x18C00E4 or 0x1B57110 <= relative < 0x1B5715C:
                require(self.allocating, "分配路径未从预期入口进入")
                return
            super()._on_code(machine, address, size, context)
        except AuditError as error:
            self.failure = str(error)
            machine.emu_stop()

    def _finish(self):
        require(self.nested == ["RustLibApiImpl.hyperApiBundleBundleNew"]
                and self.lazy_count == (1 if self.api_state == "lazy" else 0), "Bundle 转发未完成")
        expected = self.allocation_expectations() if self.scenario == "nonnull" else []
        require(self.allocation_writes == expected, "Intent 分配写入未完成或空分支仍分配")
        require(bytes(self.m.mem_read(self.top - 64, 16)) == b"\x5a" * 16, "包装器写出自己的 Dart 帧")
        super()._finish()
        self.result["对象边界"] = {"迟初始化次数": self.lazy_count, "命名转发": list(self.nested),
                                 "分配写回调数": len(self.allocation_writes)}


def run_matrix(image, flow, obj):
    pairs = 0
    for site, scenario, layout, flags, api in itertools.product(
            flow["sites"], flow_audit.MATRIX["scenarios"], flow_audit.MATRIX["layouts"],
            flow_audit.MATRIX["flags"], MATRIX["apiStates"]):
        args = (image, flow, obj, site)
        direct = ObjectTrial(*args, "direct", scenario, layout, flags, api_state=api).run()
        island = ObjectTrial(*args, "island", scenario, layout, flags, api_state=api).run()
        require(direct == island, "真实对象边界的两种调用方式不等价")
        pairs += 1
    require(pairs == MATRIX["comparisons"] and pairs * 2 == MATRIX["cases"], "对象行为矩阵数目不符")
    return {"执行次数": pairs * 2, "逐项等价对照": pairs,
            "新增真实代码范围字节": 108 + 12 + 76, "池槽内容已解码": False}


def negative_controls(image, flow, obj):
    cases = [
        ("分配类 tag 改错", "分配快路径写入", {"patches": {0x18C00D8: 0xD2800002 | (0x731C << 5)}}),
        ("分配尺寸由 48 改 32", "分配快路径写入", {"patches": {0x18C00D8: 0xD2800002 | (0x821C << 5)}}),
        ("Dart null 初始化改成零", "分配快路径写入", {"patches": {0x1B57134: 0xAA1F03F0}}),
        ("迟初始化元数据槽偏移改错", "迟初始化参数", {"api_state": "lazy", "patches": {0x9A2C74: 0xF9416F62}}),
        ("转发 API 字段偏移改错", "Bundle API 参数", {"patches": {0x9A2C8C: 0xB8417020}}),
        ("运行时接口缺失", "异常构造路径未建模", {"api_state": "missing"}),
        ("堆余量恰等于对象大小", "分配慢路径未建模", {"space": "exact"}),
        ("堆余量小于对象大小", "分配慢路径未建模", {"space": "short"}),
    ]
    results = []
    for label, reason, kwargs in cases:
        try:
            ObjectTrial(image, flow, obj, flow["sites"][0], "island", "nonnull", **kwargs).run()
        except AuditError as error:
            require(reason in str(error), "对象对照在非预期位置失败：" + label + "：" + str(error))
            results.append({"对照": label, "已拒绝": True})
        else:
            raise AuditError("对象对照被错误放行：" + label)
    require(len(results) == MATRIX["rejections"], "对象拒绝对照数不符")
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-elf", type=Path, required=True)
    parser.add_argument("--object", type=Path, required=True)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args(argv)
    try:
        flow, pilot, returns, island = flow_audit.load_contracts()
        flow_audit.verify_contracts(flow, pilot, returns, island)
        contract = load_contract(args.contract)
        verify_contract(contract, flow)
        flow_audit.verify_isolation()
        image = ElfImage(args.host_elf.read_bytes())
        flow_audit.verify_host(image, flow)
        evidence = verify_host(image, contract, flow)
        obj = Arm64Object(args.object.read_bytes(), ISLAND_SYMBOL)
        verify_island(obj, island)
        result = {"策略": POLICY, "静态证据": evidence, "矩阵": run_matrix(image, flow, obj),
                  "拒绝对照": negative_controls(image, flow, obj), "安装许可": False,
                  "未关闭门槛": [1, 3, 5], "限制": contract["verificationBoundary"]}
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (AuditError, OSError, ValueError, KeyError, TypeError, struct.error) as error:
        print("分享对象复核失败：" + (type(error).__name__ if isinstance(error, OSError) else str(error)))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
