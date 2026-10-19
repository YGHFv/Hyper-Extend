"""离线仿真的输入、地址和策略边界；真实汇编行为由显式仿真命令验证。"""

import struct
import subprocess
import sys
import unittest
from pathlib import Path

from launcher_native_audit import AuditError
from launcher_shortcut_count_emulate import (
    Arm64Object, CONTINUATION_SYMBOL, STATE_SYMBOL, effective_limit, relocate_word, verify_trial_site,
)


def object_fixture():
    data = bytearray(0x500)
    names = b"\0.shstrtab\0.text\0.strtab\0.symtab\0.rela.text\0"
    symbols = b"\0" + CONTINUATION_SYMBOL.encode() + b"\0" + STATE_SYMBOL.encode() + b"\0"
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 1, 183, 1, 0, 0, 0x300, 0, 64, 0, 0, 64, 6, 1)
    data[0x40:0x40 + len(names)] = names
    struct.pack_into("<II", data, 0x80, 0x90000009, 0x91000129)
    data[0x100:0x100 + len(symbols)] = symbols
    struct.pack_into("<IBBHQQ", data, 0x200 + 24, 1, 0x12, 2, 2, 0, 8)
    struct.pack_into("<IBBHQQ", data, 0x200 + 48, symbols.index(STATE_SYMBOL.encode()), 0x10, 0, 0, 0, 0)
    struct.pack_into("<QQq", data, 0x260, 0, (2 << 32) | 275, 0)
    struct.pack_into("<QQq", data, 0x278, 4, (2 << 32) | 277, 0)
    sections = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (names.index(b".shstrtab"), 3, 0, 0, 0x40, len(names), 0, 0, 1, 0),
        (names.index(b".text"), 1, 6, 0, 0x80, 8, 0, 0, 4, 0),
        (names.index(b".strtab"), 3, 0, 0, 0x100, len(symbols), 0, 0, 1, 0),
        (names.index(b".symtab"), 2, 0, 0, 0x200, 72, 3, 1, 8, 24),
        (names.index(b".rela.text"), 4, 0, 0, 0x260, 48, 4, 2, 8, 24),
    ]
    for i, section in enumerate(sections):
        struct.pack_into("<IIQQQQIIQQ", data, 0x300 + i * 64, *section)
    return data


class ObjectAuditTest(unittest.TestCase):
    def test_exact_object_and_explicit_external(self):
        obj = Arm64Object(object_fixture(), CONTINUATION_SYMBOL)
        code = obj.materialize(0x2000000, {STATE_SYMBOL: 0x2100000})
        self.assertEqual(8, len(code))
        self.assertEqual(0x90000809, struct.unpack_from("<I", code)[0])

    def test_headers_truncation_and_wrong_image_types(self):
        for offset, value in [(16, 2), (16, 3), (18, 62), (56, 1), (58, 32), (62, 20)]:
            data = object_fixture()
            struct.pack_into("<H", data, offset, value)
            with self.subTest(offset=offset, value=value), self.assertRaises(AuditError):
                Arm64Object(data, CONTINUATION_SYMBOL)
        for length in (0, 63, 0x370):
            with self.assertRaises(AuditError):
                Arm64Object(object_fixture()[:length], CONTINUATION_SYMBOL)

    def test_nonunique_or_partial_function_is_refused(self):
        for offset, fmt, value in [(0x21c, "<B", 0x02), (0x21d, "<B", 0), (0x220, "<Q", 4),
                                   (0x228, "<Q", 4), (0x21e, "<H", 0)]:
            data = object_fixture()
            struct.pack_into(fmt, data, offset, value)
            with self.assertRaises(AuditError):
                Arm64Object(data, CONTINUATION_SYMBOL)
        with self.assertRaises(AuditError):
            Arm64Object(object_fixture(), "未知入口")

    def test_extra_allocated_section_is_refused(self):
        data = object_fixture()
        struct.pack_into("<Q", data, 0x300 + 3 * 64 + 8, 2)
        with self.assertRaises(AuditError):
            Arm64Object(data, CONTINUATION_SYMBOL)

    def test_relocation_must_be_unique_aligned_bounded_and_external(self):
        for offset, fmt, value in [(0x278, "<Q", 0), (0x278, "<Q", 3), (0x278, "<Q", 8),
                                   (0x268, "<Q", (100 << 32) | 275), (0x270, "<q", 1),
                                   (0x236, "<H", 2)]:
            data = object_fixture()
            struct.pack_into(fmt, data, offset, value)
            with self.subTest(offset=offset), self.assertRaises(AuditError):
                Arm64Object(data, CONTINUATION_SYMBOL)

    def test_external_set_cannot_silently_change(self):
        obj = Arm64Object(object_fixture(), CONTINUATION_SYMBOL)
        for externals in ({}, {"其他": 0}, {STATE_SYMBOL: 0, "其他": 0}):
            with self.assertRaises(AuditError):
                obj.materialize(0x2000000, externals)

    def test_unknown_relocation_and_wrong_opcode_are_refused(self):
        for offset, value in [(0x268, (2 << 32) | 999), (0x80, 0xd503201f)]:
            data = object_fixture()
            struct.pack_into("<Q" if offset == 0x268 else "<I", data, offset, value)
            with self.assertRaises(AuditError):
                Arm64Object(data, CONTINUATION_SYMBOL).materialize(0x2000000, {STATE_SYMBOL: 0x2100000})


class RelocationTest(unittest.TestCase):
    def test_branch_positive_negative_and_limit(self):
        pc = 0x10000000
        for delta in (-(1 << 27), -4, 0, 4, (1 << 27) - 4):
            word = relocate_word(0x14000000, 282, pc, pc + delta)
            encoded = word & 0x3ffffff
            actual = (encoded - (1 << 26) if encoded & (1 << 25) else encoded) * 4
            self.assertEqual(delta, actual)
        for delta in (-(1 << 27) - 4, 1 << 27, 2):
            with self.assertRaises(AuditError):
                relocate_word(0x14000000, 282, pc, pc + delta)

    def test_page_relative_and_low_bits_are_independent(self):
        pc = 0x200000000
        for pages in (-(1 << 20), -1, 0, 1, (1 << 20) - 1):
            target = pc + (pages << 12) + 0xabc
            word = relocate_word(0x90000009, 275, pc + 0x30, target)
            encoded = ((word >> 29) & 3) | (((word >> 5) & 0x7ffff) << 2)
            actual = encoded - (1 << 21) if encoded & (1 << 20) else encoded
            self.assertEqual(pages, actual)
            low = relocate_word(0x91000129, 277, pc + 0x34, target)
            self.assertEqual(0xabc, (low >> 10) & 0xfff)
        for pages in (-(1 << 20) - 1, 1 << 20):
            with self.assertRaises(AuditError):
                relocate_word(0x90000009, 275, pc, pc + (pages << 12))

    def test_wrong_opcodes_and_addresses_are_refused(self):
        for word, kind in [(0x94000000, 282), (0x10000009, 275), (0x91400129, 277), (0, 999)]:
            with self.assertRaises(AuditError):
                relocate_word(word, kind, 0x2000000, 0x2100000)
        for pc, target in [(-4, 0), (2, 0), (0, -1), (0, 1 << 64)]:
            with self.assertRaises(AuditError):
                relocate_word(0x14000000, 282, pc, target)


class TrialContractTest(unittest.TestCase):
    def test_only_5_through_20_override(self):
        for stock in (3, 4):
            for state in (*range(30), 0x7fffffff, 0x80000000, 0xffffffff):
                self.assertEqual(state if 5 <= state <= 20 else stock, effective_limit(state, stock))

    def test_invalid_model_inputs_fail_instead_of_wrapping(self):
        for state, stock in [(-1, 3), (1 << 32, 4), (10, 5), (3.5, 3)]:
            with self.assertRaises(AuditError):
                effective_limit(state, stock)

    def test_independent_site_requires_both_exact_windows(self):
        windows = {0x1147048: bytes.fromhex("017c4193a3035ff83f0003ebac000054"),
                   0x114701c: bytes.fromhex("e20300aaa1835ff8a2031ff820f05ff8007c4cd3e10100f9")}

        class Image:
            def code(self, address, size):
                return windows[address][:size]

        verify_trial_site(Image())
        for address in windows:
            original = windows[address]
            windows[address] = bytes(len(original))
            with self.assertRaises(AuditError):
                verify_trial_site(Image())
            windows[address] = original

    def test_help_does_not_require_emulator_or_local_host(self):
        script = Path(__file__).with_name("launcher_shortcut_count_emulate.py")
        result = subprocess.run([sys.executable, str(script), "--help"], capture_output=True)
        self.assertEqual(0, result.returncode)
        self.assertIn(b"--branch-object", result.stdout)


if __name__ == "__main__":
    unittest.main()
