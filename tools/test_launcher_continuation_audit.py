import copy
import struct
import unittest

from launcher_native_audit import (CONTINUATION_COVERAGE, AuditError, ElfImage, direct_branch,
                                   is_position_independent, verify_continuation)
from test_launcher_native_audit import fixture


def branch_word(base, source, target, bits=26):
    displacement = (target - source) // 4
    return base | ((displacement & ((1 << bits) - 1)) << (0 if bits == 26 else 5))


class ContinuationAuditTest(unittest.TestCase):
    def setUp(self):
        self.data = bytearray(fixture())
        self.data[0x400:0x500] = struct.pack("<I", 0xd503201f) * 64
        self.write(0x2000, branch_word(0x94000000, 0x2000, 0x2080))
        self.data[0x404:0x41c] = bytes.fromhex(CONTINUATION_COVERAGE[24])
        self.contract = {
            "functions": [{"name": "sort", "address": "0x2000", "size": 0x80},
                          {"name": "getter", "address": "0x2080", "size": 0x80}],
            "continuation": {"policy": "post-getter-raw-limit-v1", "owner": "sort", "getter": "getter",
                             "callAddress": "0x2000", "address": "0x2004", "coverageBytes": 24,
                             "resumeAddress": "0x201c"},
        }

    def write(self, address, word):
        struct.pack_into("<I", self.data, 0x400 + address - 0x2000, word)

    def verify(self):
        return verify_continuation(ElfImage(bytes(self.data)), self.contract)

    def test_exact_candidate_is_not_installation_permission(self):
        report = self.verify()
        self.assertFalse(report["安装许可"])
        self.assertEqual(256, report["扫描执行字节数"])
        self.assertEqual(0, report["窗口直接入口数"])
        self.assertEqual(1, report["getter直接入口数"])

    def test_all_direct_branch_families_decode_forward_and_backward(self):
        families = [(0x14000000, 26, "B"), (0x94000000, 26, "BL"),
                    (0x54000000, 19, "B.cond/BC.cond"), (0x54000010, 19, "B.cond/BC.cond"),
                    (0x34000000, 19, "CBZ"), (0xb4000000, 19, "CBZ"),
                    (0x35000000, 19, "CBNZ"), (0xb5000000, 19, "CBNZ"),
                    (0x36000000, 14, "TBZ"), (0xb7000000, 14, "TBNZ")]
        for base, bits, kind in families:
            for offset in (0, 4, -4, (1 << (bits - 1)) * 4 - 4, -(1 << (bits - 1)) * 4):
                with self.subTest(base=hex(base), offset=offset):
                    source = 0x10000000
                    word = branch_word(base, source, source + offset, bits)
                    self.assertEqual((kind, source + offset), direct_branch(word, source))

    def test_non_direct_instructions_are_not_fabricated_edges(self):
        for word in [0xd61f0000, 0xd63f0000, 0xd65f03c0, 0x10000000, 0x90000000,
                     0x58000000, 0xd503201f, 0xaa0003e2, 0xf85f83a1]:
            self.assertIsNone(direct_branch(word, 0x2000))

    def test_every_window_entry_including_start_is_rejected(self):
        original = bytes(self.data)
        for base, bits in [(0x14000000, 26), (0x94000000, 26), (0x54000000, 19),
                           (0x54000010, 19), (0xb4000000, 19), (0xb5000000, 19),
                           (0x36000000, 14), (0xb7000000, 14)]:
            for target in range(0x2004, 0x201c, 4):
                self.data = bytearray(original)
                self.write(0x2040, branch_word(base, 0x2040, target, bits))
                with self.subTest(base=hex(base), target=hex(target)), self.assertRaises(AuditError):
                    self.verify()

    def test_branch_to_resume_does_not_enter_overwritten_window(self):
        self.write(0x2040, branch_word(0x14000000, 0x2040, 0x201c))
        self.assertEqual(0, self.verify()["窗口直接入口数"])

    def test_extra_getter_caller_is_rejected(self):
        for base in (0x14000000, 0x94000000):
            self.write(0x2040, branch_word(base, 0x2040, 0x2080))
            with self.assertRaises(AuditError):
                self.verify()

    def test_call_cannot_be_tail_branch_or_another_target(self):
        for base, target in [(0x14000000, 0x2080), (0x94000000, 0x2084)]:
            self.write(0x2000, branch_word(base, 0x2000, target))
            with self.assertRaises(AuditError):
                self.verify()

    def test_relocation_sensitive_and_changed_replay_words_are_rejected(self):
        original = bytes(self.data)
        for address in range(0x2004, 0x201c, 4):
            for word in (0x10000000, 0x90000000, 0x58000000, 0xd503201f, 0xd63f0000):
                self.data = bytearray(original)
                self.write(address, word)
                with self.assertRaises(AuditError):
                    self.verify()

    def test_contract_cannot_silently_select_another_strategy_or_coverage(self):
        original = copy.deepcopy(self.contract)
        for key, value in [("policy", "getter-entry"), ("coverageBytes", 4), ("coverageBytes", 8),
                           ("coverageBytes", 20), ("coverageBytes", 32),
                           ("address", "0x2008"), ("resumeAddress", "0x2020"),
                           ("callAddress", "0x2004"), ("owner", "missing"), ("getter", "missing")]:
            self.contract = copy.deepcopy(original)
            self.contract["continuation"][key] = value
            with self.assertRaises(AuditError):
                self.verify()

    def test_approved_coverage_footprints_match_dobby_trampolines(self):
        # 12/16 字节是 Dobby 近距分支与远距分支的短前缀；24 字节是远距分支完整足迹，
        # 本项目取 24。三者的共同前提是覆盖窗口内全部为位置无关指令。
        self.assertEqual({12, 16, 24}, set(CONTINUATION_COVERAGE))
        for size, prefix in CONTINUATION_COVERAGE.items():
            raw = bytes.fromhex(prefix)
            self.assertEqual(size, len(raw), "覆盖足迹长度与键不一致")
            self.assertTrue(all(is_position_independent(word)
                                for (word,) in struct.iter_unpack("<I", raw)),
                            "已审核覆盖前缀不得包含位置相关指令")
            self.assertTrue(prefix.startswith(CONTINUATION_COVERAGE[12]))

    def test_resume_and_call_must_remain_in_owner(self):
        for start, size in [(0x2004, 0x80), (0x2000, 0x1c)]:
            self.contract["functions"][0].update(address=start, size=size)
            with self.assertRaises(AuditError):
                self.verify()

    def test_writable_execution_and_partial_instruction_segments_are_rejected(self):
        for field, value in [(4, 7), (32, 255)]:
            original = bytes(self.data)
            struct.pack_into("<I" if field == 4 else "<Q", self.data, 64 + 56 + field, value)
            with self.assertRaises(AuditError):
                self.verify()
            self.data = bytearray(original)


if __name__ == "__main__":
    unittest.main()
