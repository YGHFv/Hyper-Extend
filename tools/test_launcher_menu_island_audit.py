"""蹦床语义审计器的合成用例。

真实宿主与真实交叉汇编对象由审计命令显式验证，不以跳过代替通过。这里用可控的
合成映像与合成对象覆盖正向通过路径与各类拒绝路径。
"""

import copy
import hashlib
import json
import struct
import unittest
from pathlib import Path

from launcher_native_audit import AuditError, digest
from launcher_menu_island_audit import (CALLER, ISLAND, ORIGINAL_SYMBOL, POLICY,
                                        RET, SLOT, TARGET, Trial, branch26, encode_add_lo12,
                                        encode_adrp, materialize, negative_controls,
                                        run_matrix, sentinel_registers, verify_island)
from launcher_shortcut_count_emulate import Arm64Object


ISLAND_SYMBOL = "launcher_menu_island"
WORDS = (0x90000010, 0x91000210, 0xF9400210, 0xD61F0200)


def object_fixture(words=WORDS, relocations=((0, 275), (4, 277)), entry=ISLAND_SYMBOL):
    code = struct.pack("<%dI" % len(words), *words)
    data = bytearray(0x500)
    names = b"\0.shstrtab\0.text\0.strtab\0.symtab\0.rela.text\0"
    symbols = b"\0" + entry.encode() + b"\0" + ORIGINAL_SYMBOL.encode() + b"\0"
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 1, 183, 1, 0, 0, 0x300, 0, 64, 0, 0, 64, 6, 1)
    data[0x40:0x40 + len(names)] = names
    data[0x80:0x80 + len(code)] = code
    data[0x100:0x100 + len(symbols)] = symbols
    struct.pack_into("<IBBHQQ", data, 0x200 + 24, 1, 0x12, 2, 2, 0, len(code))
    struct.pack_into("<IBBHQQ", data, 0x200 + 48,
                     symbols.index(ORIGINAL_SYMBOL.encode()), 0x10, 0, 0, 0, 0)
    for index, (offset, kind) in enumerate(relocations):
        struct.pack_into("<QQq", data, 0x260 + index * 24, offset,
                         (2 << 32) | kind, 0)
    sections = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (names.index(b".shstrtab"), 3, 0, 0, 0x40, len(names), 0, 0, 1, 0),
        (names.index(b".text"), 1, 6, 0, 0x80, len(code), 0, 0, 4, 0),
        (names.index(b".strtab"), 3, 0, 0, 0x100, len(symbols), 0, 0, 1, 0),
        (names.index(b".symtab"), 2, 0, 0, 0x200, 72, 3, 1, 8, 24),
        (names.index(b".rela.text"), 4, 0, 0, 0x260, 24 * len(relocations), 4, 2, 8, 24),
    ]
    for i, section in enumerate(sections):
        struct.pack_into("<IIQQQQIIQQ", data, 0x300 + i * 64, *section)
    return bytes(data)


def island_object(**kwargs):
    return Arm64Object(object_fixture(**kwargs), ISLAND_SYMBOL)


def contract_for(obj, sites=None):
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "island": {
            "symbol": ISLAND_SYMBOL,
            "size": len(obj.code),
            "sha256": digest(obj.code),
            "words": [hex(word) for word in WORDS],
            "relocations": [[offset, kind, ORIGINAL_SYMBOL]
                            for offset, kind in ((0, 275), (4, 277))],
        },
        "sites": sites if sites is not None else [{
            "feature": "数量点", "callOffset": "0x1147018", "word": "0x94000137",
            "target": "0x11474f4", "targetName": "计数 getter",
            "entryWord": "0xa9bf79fd", "prologueWords": ["0xa9bf79fd", "0xaa0f03fd"],
            "argumentConvention": "getter-after-call"}],
        "counts": {"sites": 1, "negativeControls": 3},
    }


class EncoderTest(unittest.TestCase):
    def test_adrp_preserves_destination_register(self):
        word = encode_adrp(0x90000010, ISLAND, SLOT)
        # 这里曾编出 `adrp x0`：目标寄存器被清零，蹦床拿不到原目标地址。
        self.assertEqual(0x10, word & 0x1F)
        self.assertEqual(0x90010010, word)
        self.assertEqual(0x90010000 | 0x10, word)

    def test_adrp_rejects_non_adrp_and_out_of_range(self):
        with self.assertRaises(AuditError):
            encode_adrp(0x91000210, ISLAND, SLOT)
        with self.assertRaises(AuditError):
            encode_adrp(0x90000010, ISLAND, ISLAND + (1 << 32))

    def test_add_lo12_keeps_opcode_and_registers(self):
        word = encode_add_lo12(0x91000210, SLOT | 0xABC)
        self.assertEqual(0x91000210 & ~0x003FFC00, word & ~0x003FFC00)
        self.assertEqual((0xABC << 10), word & 0x003FFC00)
        with self.assertRaises(AuditError):
            encode_add_lo12(0xF9400210, SLOT)

    def test_branch26_link_bit_and_range(self):
        self.assertEqual(0x94000000 | ((SLOT - ISLAND) // 4),
                         branch26(ISLAND, SLOT, True))
        self.assertEqual(0x14000000 | ((SLOT - ISLAND) // 4),
                         branch26(ISLAND, SLOT, False))
        with self.assertRaises(AuditError):
            branch26(ISLAND, ISLAND + (1 << 27), True)
        with self.assertRaises(AuditError):
            branch26(ISLAND, ISLAND + 2, True)


class IslandContractTest(unittest.TestCase):
    def setUp(self):
        self.obj = island_object()
        self.contract = contract_for(self.obj)

    def test_reviewed_island_passes(self):
        result = verify_island(self.obj, self.contract)
        self.assertEqual(16, result["字节数"])
        self.assertEqual("br（不写 x30）", result["尾跳"])

    def test_install_permission_or_wrong_policy_is_refused(self):
        for key, value in (("installPermitted", True), ("policy", "other"),
                           ("schemaVersion", 2)):
            broken = copy.deepcopy(self.contract)
            broken[key] = value
            with self.subTest(key=key), self.assertRaises(AuditError):
                verify_island(self.obj, broken)

    def test_changed_word_or_size_or_digest_is_refused(self):
        for target, value in (("words", ["0x90000010", "0x91000210",
                                         "0xf9400210", "0xd65f03c0"]),
                              ("size", 12), ("sha256", "0" * 64)):
            broken = copy.deepcopy(self.contract)
            broken["island"][target] = value
            with self.subTest(target=target), self.assertRaises(AuditError):
                verify_island(self.obj, broken)

    def test_relocation_set_cannot_change(self):
        broken = copy.deepcopy(self.contract)
        broken["island"]["relocations"] = [[0, 275, ORIGINAL_SYMBOL]]
        with self.assertRaises(AuditError):
            verify_island(self.obj, broken)

    def test_unreviewed_external_symbol_is_refused(self):
        obj = island_object(relocations=((0, 275), (4, 275)))
        with self.assertRaises(AuditError):
            # 两个 ADRP 指向同名符号，符号集合仍唯一，但机器字与合同不符。
            verify_island(obj, contract_for(obj))

    def test_island_must_not_contain_ret_or_bl(self):
        for words in ((0x90000010, 0x91000210, 0xF9400210, 0xD65F03C0),
                      (0x94000000, 0x91000210, 0xF9400210, 0xD61F0200)):
            obj = island_object(words=words)
            broken = contract_for(obj)
            with self.subTest(words=words), self.assertRaises(AuditError):
                verify_island(obj, broken)

    def test_tail_must_be_register_branch(self):
        obj = island_object(words=(0x90000010, 0x91000210, 0xF9400210, 0xD503201F))
        with self.assertRaises(AuditError):
            verify_island(obj, contract_for(obj))


class MaterializeTest(unittest.TestCase):
    def test_external_set_must_match_exactly(self):
        obj = island_object()
        for externals in ({}, {ORIGINAL_SYMBOL: SLOT, "其他": SLOT}):
            with self.assertRaises(AuditError):
                materialize(obj, ISLAND, externals)

    def test_materialized_words_target_the_slot(self):
        obj = island_object()
        code = materialize(obj, ISLAND, {ORIGINAL_SYMBOL: SLOT})
        self.assertEqual(0x10, struct.unpack_from("<I", code)[0] & 0x1F)
        self.assertEqual(WORDS[1:], struct.unpack_from("<3I", code, 4))

    def test_unknown_relocation_kind_is_refused(self):
        obj = island_object(relocations=((0, 999),))
        with self.assertRaises(AuditError):
            materialize(obj, ISLAND, {ORIGINAL_SYMBOL: SLOT})


class IslandExecutionTest(unittest.TestCase):
    def setUp(self):
        self.obj = island_object()
        self.contract = contract_for(self.obj)

    def test_island_is_transparent_for_every_flag_combination(self):
        cases = run_matrix(self.obj, self.contract, [0, 1, 7, 8, 15])
        self.assertEqual(5, len(cases))
        for case in cases:
            self.assertTrue(case["仅 x16/x17 可变"])
            self.assertEqual("保持调用点+4", case["x30"])

    def test_broken_islands_are_detected(self):
        probes = negative_controls(self.obj, self.contract, 0)
        self.assertEqual(3, len(probes))
        self.assertTrue(all(item["已检出"] for item in probes))


if __name__ == "__main__":
    unittest.main()
