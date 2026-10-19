"""菜单宿主审计工具的合成用例：锁定指令长度解码与挂载点解析。

真实宿主只由审计命令显式验证，不以跳过代替通过。
"""

import struct
import unittest

from launcher_native_audit import AuditError
from launcher_menu_host_audit import (FEATURE_MOUNTS, Dex, INSTRUCTION_SIZES,
                                      FLAG_ACTIVITY_MULTIPLE_TASK, menu_mounts,
                                      verify_task_mapping)


def code_item(insns):
    """构造最小 code_item：16 字节头 + 指令字。"""
    assert len(insns) % 2 == 0
    return struct.pack("<HHHHI", 5, 0, 0, 0, 0) + struct.pack("<I", len(insns) // 2) + insns


def build_dex(code, strings, type_strings, method_refs, field_refs=()):
    """构造只含 Dex 解析所需表的合成 DEX，返回 (bytes, code_off)。"""
    strings_off = 0x70
    types_off = strings_off + 4 * len(strings)
    fields_off = types_off + 4 * len(type_strings)
    methods_off = fields_off + 8 * len(field_refs)
    classes_off = methods_off + 8 * len(method_refs)
    code_off = classes_off
    data_off = code_off + len(code)
    data = bytearray(data_off)
    blob = bytearray()
    string_offsets = []
    cursor = data_off
    for text in strings:
        encoded = text.encode("utf-8")
        assert len(text) < 128
        string_offsets.append(cursor)
        blob += bytes([len(text)]) + encoded + b"\0"
        cursor += 1 + len(encoded) + 1
    data[0:8] = b"dex\n035\0"
    struct.pack_into("<I", data, 0x38, len(strings))
    struct.pack_into("<I", data, 0x3C, strings_off)
    struct.pack_into("<I", data, 0x40, len(type_strings))
    struct.pack_into("<I", data, 0x44, types_off)
    struct.pack_into("<I", data, 0x50, len(field_refs))
    struct.pack_into("<I", data, 0x54, fields_off)
    struct.pack_into("<I", data, 0x58, len(method_refs))
    struct.pack_into("<I", data, 0x5C, methods_off)
    struct.pack_into("<I", data, 0x60, 0)
    struct.pack_into("<I", data, 0x64, classes_off)
    for index, offset in enumerate(string_offsets):
        struct.pack_into("<I", data, strings_off + index * 4, offset)
    for index, string_index in enumerate(type_strings):
        struct.pack_into("<I", data, types_off + index * 4, string_index)
    for index, (class_index, name_index) in enumerate(field_refs):
        struct.pack_into("<HHI", data, fields_off + index * 8, class_index, 0, name_index)
    for index, (class_index, name_index) in enumerate(method_refs):
        struct.pack_into("<HHI", data, methods_off + index * 8, class_index, 0, name_index)
    data[code_off:code_off + len(code)] = code
    data[data_off:data_off + len(blob)] = blob
    return bytes(data), code_off


STRINGS = ["Lcom/miui/home/X;", "foo", "Lcom/example/Y;"]
METHOD_REFS = [(1, 1)]  # Lcom/example/Y;->foo
TYPE_STRINGS = [0, 2]   # 类型 0 = 宿主串，类型 1 = 参考串


class InstructionDecodeTest(unittest.TestCase):
    def setUp(self):
        # const-string v0,#0 ; new-instance v0,type@1 ; invoke method@0。
        # 末条 31i 的立即数低 16 位恰为 0x001A，模拟朴素 2 字节盲扫会误判的字节。
        insns = bytes.fromhex("1A000000" "22000100" "6E0000000000" "14001A003412")
        data, code_off = build_dex(code_item(insns), STRINGS, TYPE_STRINGS, METHOD_REFS)
        self.dex = Dex(data)
        self.code_off = code_off
        self.expected_strings = ["Lcom/miui/home/X;"]
        self.expected_types = ["Lcom/example/Y;"]
        self.expected_methods = ["Lcom/example/Y;->foo"]

    def test_length_aware_decode_is_exact(self):
        refs = self.dex.method_refs(self.code_off)
        self.assertEqual(self.expected_strings, refs["strings"])
        self.assertEqual(self.expected_types, refs["types"])
        self.assertEqual(self.expected_methods, refs["methods"])
        self.assertEqual(10, refs["insns"])

    def test_naive_two_byte_scan_would_fabricate_and_crash(self):
        """故障对照：证明旧盲扫实现会把立即数当 opcode 并解出越界下标。"""
        insns_size = self.dex.u32(self.code_off + 12)
        start = self.code_off + 16
        with self.assertRaises(AuditError):
            for cursor in range(start, start + insns_size * 2, 2):
                word = struct.unpack_from("<H", self.dex.data, cursor)[0]
                if word in (0x1A, 0x1B):
                    self.dex.string(struct.unpack_from("<H", self.dex.data, cursor + 2)[0])

    def test_undefined_opcode_is_error(self):
        data, code_off = build_dex(code_item(bytes.fromhex("3E00")), STRINGS,
                                   TYPE_STRINGS, METHOD_REFS)
        with self.assertRaises(AuditError):
            Dex(data).method_refs(code_off)

    def test_instruction_past_code_end_is_error(self):
        data, code_off = build_dex(code_item(bytes.fromhex("18000000")), STRINGS,
                                   TYPE_STRINGS, METHOD_REFS)
        with self.assertRaises(AuditError):
            Dex(data).method_refs(code_off)

    def test_all_opcodes_referenced_are_sized(self):
        self.assertEqual(256, len(INSTRUCTION_SIZES))
        for opcode in (0x00, 0x18, 0x1A, 0x1B, 0x22, 0x6E, 0x70, 0x78, 0xFF):
            self.assertIsNotNone(INSTRUCTION_SIZES[opcode], hex(opcode))
        for opcode in (0x3E, 0x43, 0x73, 0x79, 0x7A, 0xE3, 0xF9):
            self.assertIsNone(INSTRUCTION_SIZES[opcode], hex(opcode))


class MenuMountTest(unittest.TestCase):
    def symbols(self, drop=None):
        rows = [(0x1000 + index * 0x10, 12 + index, name)
                for index, (_, names) in enumerate(FEATURE_MOUNTS) for name in names]
        rows.append((0x9000, 8, "ShortcutMenuGetXController._getAppShortcutMenuItemList"))
        rows.append((0xA000, 40, "GestureGlobalController.isLaunchingNewTask="))
        return [row for row in rows if row[2] != drop]

    def test_all_feature_mounts_resolve(self):
        report = menu_mounts(self.symbols())
        self.assertEqual({}, report["未能唯一的挂载点"])
        features = report["四项功能挂载点"]
        for label, names in FEATURE_MOUNTS:
            self.assertEqual(set(names), set(features[label]), label)
        self.assertEqual("0x1000", features["数量点"][
            "ShortcutMenuGetXController._getMaxCountInCurrentOrientation"]["address"])

    def test_missing_mount_is_reported_not_guessed(self):
        target = "ValidSmallWindowShortcutMenu.onItemClick"
        report = menu_mounts(self.symbols(drop=target))
        self.assertIn(target, report["未能唯一的挂载点"]["小窗"])
        self.assertNotIn(target, report["四项功能挂载点"]["小窗"])

    def test_required_menu_types_changing_is_error(self):
        rows = [(0x1000, 12, "ShareAppShortcutMenuItem.getShortcutMenuType")]
        with self.assertRaises(AuditError):
            menu_mounts(rows)

    def test_new_task_is_probed_explicitly(self):
        report = menu_mounts(self.symbols())
        self.assertIn("GestureGlobalController.isLaunchingNewTask=",
                      report["新任务独立菜单项探测"]["NewTask"])
        self.assertEqual([], report["新任务独立菜单项探测"]["FreeformShortcut"])


def movz_imm(value):
    """构造 64 位 MOVZ 立即数装载：mov xN, #value，按需左移 16 位半字。"""
    shift = 0
    while shift < 4 and (value >> (16 * (shift + 1))) != 0:
        shift += 1
    assert (value >> (16 * shift)) & 0xFFFF == value >> (16 * shift), value
    assert value == ((value >> (16 * shift)) & 0xFFFF) << (16 * shift), value
    word = 0xD2800000 | (((value >> (16 * shift)) & 0xFFFF) << 5) | (shift << 21)
    return struct.pack("<I", word)


def task_symbols():
    """小窗 setFlags 0x10200000，多开 setFlags 0x18200000，各 mov+stur。"""
    return {
        0x1000: movz_imm(0x10200000) + struct.pack("<I", 0xF800F020),
        0x2000: movz_imm(0x18200000) + struct.pack("<I", 0xF800F020),
    }


class TaskMappingFixture:
    def __init__(self, windows=None):
        self.windows = dict(task_symbols())
        if windows:
            self.windows.update(windows)

    def code(self, address, size):
        raw = self.windows[address]
        if len(raw) != size:
            raise AuditError("测试指令大小不一致")
        return raw


def task_contract(windows=None, diff=FLAG_ACTIVITY_MULTIPLE_TASK):
    """默认合同与 `TaskMappingFixture` 的字节一致；可传入自定义窗口。"""
    windows = task_symbols() if windows is None else windows
    declared = {0x1000: "0x10200000", 0x2000: "0x18200000"}
    return {"taskMapping": {
        "flagDiff": hex(diff), "flagDiffName": "Intent.FLAG_ACTIVITY_MULTIPLE_TASK",
        "windows": [
            {"name": "小窗", "address": address,
             "bytes": windows[address].hex(),
             "expectedImmediate": declared[address]}
            for address in (0x1000, 0x2000)
        ],
    }}


class TaskMappingTest(unittest.TestCase):
    def test_small_window_and_new_task_differ_only_by_multiple_task(self):
        result = verify_task_mapping(TaskMappingFixture(), task_contract())
        self.assertEqual("0x10200000", result["小窗 setFlags 立即数"])
        self.assertEqual("0x18200000", result["新任务(多开) setFlags 立即数"])
        self.assertEqual(hex(FLAG_ACTIVITY_MULTIPLE_TASK), result["差值"])

    def test_missing_task_mapping_is_error(self):
        with self.assertRaisesRegex(AuditError, "taskMapping"):
            verify_task_mapping(TaskMappingFixture(), {})

    def test_wrong_declared_diff_is_error(self):
        with self.assertRaisesRegex(AuditError, "FLAG_ACTIVITY_MULTIPLE_TASK"):
            verify_task_mapping(TaskMappingFixture(), task_contract(diff=0x10000000))

    def test_changed_immediate_is_error(self):
        # 多开立即数被改成别的值（0x18210000）：窗口字节与合同一致，
        # 但解出的立即数与合同声明的 0x18200000 不符。
        fixture = TaskMappingFixture({0x2000: movz_imm(0x18210000)
                                      + struct.pack("<I", 0xF800F020)})
        with self.assertRaisesRegex(AuditError, "立即数"):
            verify_task_mapping(fixture, task_contract(windows=fixture.windows))

    def test_equal_immediates_is_error(self):
        # 两条立即数相同：差值 0，不是多任务标志，必须拒绝。
        fixture = TaskMappingFixture({0x2000: movz_imm(0x10200000)
                                      + struct.pack("<I", 0xF800F020)})
        contract = task_contract(windows=fixture.windows)
        contract["taskMapping"]["windows"][1]["expectedImmediate"] = "0x10200000"
        with self.assertRaisesRegex(AuditError, "差值"):
            verify_task_mapping(fixture, contract)

    def test_non_movz_head_is_error(self):
        broken = struct.pack("<I", 0xAA0003E1) + struct.pack("<I", 0xF800F020)
        fixture = TaskMappingFixture({0x1000: broken})
        contract = task_contract(windows=fixture.windows)
        contract["taskMapping"]["windows"][0]["expectedImmediate"] = "0x10200000"
        with self.assertRaisesRegex(AuditError, "MOVZ"):
            verify_task_mapping(fixture, contract)


if __name__ == "__main__":
    unittest.main()
