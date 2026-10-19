"""使用合成 ELF 覆盖拒绝路径；真实宿主由审计命令显式验证，不以跳过代替通过。"""

import copy
import lzma
import struct
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from launcher_native_audit import (AuditError, ElfImage, digest, direct_call_target,
                                   span, verify_contract, verify_disassembly, verify_mcp)


def fixture(debug=None, duplicate=False, needed="libc.so"):
    data = bytearray(0x1c00)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    names = (b"\0.shstrtab\0.strtab\0.symtab\0.text\0.note.gnu.build-id\0.gnu_debugdata\0"
             b".dynstr\0.dynamic\0")
    sections = [(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                (names.index(b".shstrtab"), 3, 0, 0, 0x500, len(names), 0, 0, 1, 0),
                (names.index(b".strtab"), 3, 0, 0, 0x580, 6, 0, 0, 1, 0),
                (names.index(b".symtab"), 2, 0, 0, 0x600, 72 if duplicate else 48, 2, 0, 8, 24),
                (names.index(b".text"), 1, 6, 0x2000, 0x400, 16, 0, 0, 4, 0),
                (names.index(b".note.gnu.build-id"), 7, 2, 0x200, 0x200, 20, 0, 0, 4, 0)]
    # 复制门禁要读 DT_NEEDED，因此每个夹具都必须有 .dynstr/.dynamic。库名固定放在
    # dynstr 偏移 1，便于测试传入一个「不该出现」的依赖名。
    dynstr = b"\0" + needed.encode() + b"\0"
    dynstr_index = len(sections) + (0 if debug is not None else 0)
    sections.append((names.index(b".dynstr"), 3, 0, 0, 0x548, len(dynstr), 0, 0, 1, 0))
    dynamic_index = len(sections)
    sections.append((names.index(b".dynamic"), 6, 3, 0, 0x680, 0x20, dynstr_index, 0, 8, 16))
    if debug is not None:
        require_size = 0x800 + len(debug)
        assert require_size < 0x1800
        data[0x800:require_size] = debug
        sections.append((names.index(b".gnu_debugdata"), 1, 0, 0, 0x800, len(debug), 0, 0, 1, 0))
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, 183, 1, 0, 64, 0x1800, 0, 64, 56, 3, 64, len(sections), 1)
    for index, values in enumerate([(1, 4, 0, 0, 0, 0x300, 0x300, 0x100),
                                    (1, 5, 0x400, 0x2000, 0, 0x100, 0x100, 0x100),
                                    (4, 4, 0x200, 0x200, 0, 20, 20, 4)]):
        struct.pack_into("<IIQQQQQQ", data, 64 + index * 56, *values)
    data[0x200:0x214] = struct.pack("<III", 4, 4, 3) + b"GNU\0" + b"\x12\x34\x56\x78"
    data[0x400:0x410] = struct.pack("<IIII", 0x94000002, 0xd2800080, 0xd65f03c0, 0xd503201f)
    data[0x500:0x500 + len(names)] = names
    data[0x580:0x586] = b"\0demo\0"
    data[0x548:0x548 + len(dynstr)] = dynstr
    struct.pack_into("<qQ", data, 0x680, 1, 1)
    struct.pack_into("<qQ", data, 0x690, 0, 0)
    entry = struct.pack("<IBBHQQ", 1, 2, 0, 4, 0x2000, 16)
    data[0x618:0x630] = entry
    if duplicate:
        data[0x630:0x648] = entry
    for index, section in enumerate(sections):
        struct.pack_into("<IIQQQQIIQQ", data, 0x1800 + index * 64, *section)
    return bytes(data)


def sample(debug=None):
    return ElfImage(fixture(lzma.compress(fixture() if debug is None else debug)))


def contract_for(image):
    return {"schemaVersion": 1, "host": {"packageName": "com.miui.home", "versionCode": 1},
            "image": {"sha256": digest(image.data), "buildId": "12345678", "locator": "zip_entry:lib/arm64-v8a/libapp.so"},
            "functions": [{"name": "demo", "address": "0x2000", "size": 16, "sha256": digest(image.code(0x2000, 16))}],
            "calls": [{"owner": "demo", "address": "0x2000", "target": "0x2008"}],
            "windows": [{"name": "返回", "address": "0x2004", "bytes": image.code(0x2004, 4).hex()}]}


class ElfAuditTest(unittest.TestCase):
    def setUp(self):
        self.image = sample()
        self.contract = contract_for(self.image)

    def modified(self, offset, value, fmt="<Q"):
        data = bytearray(self.image.data)
        struct.pack_into(fmt, data, offset, value)
        return bytes(data)

    def test_valid_contract(self):
        self.assertEqual({"函数数": 1, "调用边数": 1, "指令窗口数": 1}, verify_contract(self.image, self.contract))

    def test_distinct_load_bias_and_non_executable_mapping(self):
        self.assertEqual(0x404, self.image.file_offset(0x2004, 4, executable=True))
        self.assertEqual(0x200, self.image.file_offset(0x200, 4))
        with self.assertRaises(AuditError):
            self.image.code(0x200, 4)

    def test_truncated_header_tables_and_segment(self):
        for data in [self.image.data[:32], self.image.data[:0x1900],
                     self.modified(64 + 32, len(self.image.data) + 1)]:
            with self.subTest(size=len(data)), self.assertRaises(AuditError):
                ElfImage(data)

    def test_wrong_architecture_endian_and_type(self):
        for off, value, fmt in [(18, 62, "<H"), (5, 2, "<B"), (4, 1, "<B"), (16, 2, "<H")]:
            with self.subTest(offset=off), self.assertRaises(AuditError):
                ElfImage(self.modified(off, value, fmt))

    def test_overlap_alignment_and_overflow_rejected(self):
        for data in [self.modified(64 + 56 + 16, 0x200),
                     self.modified(64 + 56 + 48, 3),
                     self.modified(64 + 56 + 16, (1 << 64) - 4)]:
            with self.assertRaises(AuditError):
                ElfImage(data)
        for start, size in [(-1, 4), ((1 << 64) - 4, 8), (1 << 64, 0)]:
            with self.assertRaises(AuditError):
                span(start, size)

    def test_bss_and_unaligned_or_empty_code_not_accepted(self):
        image = ElfImage(self.modified(64 + 56 + 40, 0x200))
        for address, size in [(0x2100, 4), (0x2001, 4), (0x2000, 0), (0x2000, 3)]:
            with self.assertRaises(AuditError):
                image.code(address, size)

    def test_build_id_missing_duplicate_or_truncated(self):
        for data in [self.modified(64 + 112, 0, "<I"), self.modified(0x204, 200, "<I")]:
            with self.assertRaises(AuditError):
                ElfImage(data).build_id()
        image = sample()
        image.segments.append(dict(image.segments[-1]))
        with self.assertRaises(AuditError):
            image.build_id()

    def test_changed_contract_is_rejected_at_each_layer(self):
        changes = [("schemaVersion", None, 2), ("image", "sha256", "0" * 64),
                   ("image", "buildId", "00000000"), ("functions", "address", "0x2004"),
                   ("functions", "size", 12), ("functions", "sha256", "0" * 64),
                   ("functions", "name", "missing"), ("calls", "target", "0x200c"),
                   ("calls", "owner", "missing"), ("calls", "address", "0x2004"),
                   ("windows", "bytes", "00000000")]
        for group, key, value in changes:
            changed = copy.deepcopy(self.contract)
            if key is None:
                changed[group] = value
            elif isinstance(changed[group], list):
                changed[group][0][key] = value
            else:
                changed[group][key] = value
            with self.subTest(group=group, key=key), self.assertRaises(AuditError):
                verify_contract(self.image, changed)

    def test_single_byte_mutation_rejected(self):
        image = ElfImage(self.modified(0x404, 0, "<B"))
        with self.assertRaises(AuditError):
            verify_contract(image, self.contract)

    def test_duplicate_symbol_and_empty_contract_rejected(self):
        image = sample(fixture(duplicate=True))
        with self.assertRaises(AuditError):
            verify_contract(image, contract_for(image))
        for key in ("functions", "calls", "windows"):
            changed = copy.deepcopy(self.contract)
            changed[key] = []
            with self.assertRaises(AuditError):
                verify_contract(self.image, changed)

    def test_symbol_and_string_bounds_rejected(self):
        debug = bytearray(fixture())
        struct.pack_into("<I", debug, 0x618, 200)
        with self.assertRaises(AuditError):
            sample(bytes(debug)).debug_image().functions()
        debug = bytearray(fixture())
        struct.pack_into("<I", debug, 0x1800 + 3 * 64 + 40, 999)
        with self.assertRaises(AuditError):
            sample(bytes(debug)).debug_image().functions()

    def test_debug_payload_boundaries(self):
        compressed = lzma.compress(fixture())
        for payload in [compressed[:-1], compressed + b"extra"]:
            with self.assertRaises((AuditError, lzma.LZMAError)):
                ElfImage(fixture(payload)).debug_image()
        with patch("launcher_native_audit.DEBUG_LIMIT", 100), self.assertRaises(AuditError):
            self.image.debug_image()

    def test_signed_bl_displacement_and_non_call(self):
        self.assertEqual(0x1000, direct_call_target(0x1004, struct.pack("<I", 0x97ffffff)))
        with self.assertRaises(AuditError):
            direct_call_target(0x1004, struct.pack("<I", 0x17ffffff))

    def test_missing_real_sample_is_error_not_skip(self):
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("launcher_native_audit.py")),
                                 "--elf", str(Path(__file__).with_name("不存在的宿主.so"))], capture_output=True)
        self.assertEqual(1, result.returncode)


class McpAuditTest(unittest.TestCase):
    def setUp(self):
        self.image = sample()
        self.contract = contract_for(self.image)
        self.data = {"mapping": {"fileOffset": "0x400"}, "disassemblyText": "\n".join(
            f"0x{0x2000 + i * 4:x}: {self.image.code(0x2000 + i * 4, 4).hex()} 指令" for i in range(4))}

    def test_full_disassembly(self):
        self.assertEqual(16, verify_disassembly(self.image, self.data, 0x2000, 16))

    def test_disassembly_rejects_partial_wrong_address_mapping_and_bytes(self):
        variants = [dict(self.data, truncated=True), dict(self.data, pagination={"hasMore": True}),
                    dict(self.data, mapping={"fileOffset": "0x2000"}),
                    dict(self.data, disassemblyText=self.data["disassemblyText"].replace("0x2004:", "0x2000:")),
                    dict(self.data, disassemblyText=self.data["disassemblyText"].replace("02000094", "00000000")),
                    dict(self.data, disassemblyText=self.data["disassemblyText"].splitlines()[0])]
        for data in variants:
            with self.assertRaises(AuditError):
                verify_disassembly(self.image, data, 0x2000, 16)

    def test_mcp_uses_only_fixed_read_call_and_fresh_identity(self):
        session = Mock()
        session.request.return_value = {"structuredContent": {"ok": True, "data": self.data}}
        with patch("mt_systemui_probe.MtSession", return_value=session), patch("mt_systemui_probe.open_host") as host:
            host.return_value = {"workspaceId": "fresh", "counts": {"dexEntries": 0, "classes": 0}}
            self.assertEqual(16, verify_mcp(self.image, self.contract, "测试地址", "old")["核验字节数"])
            host.assert_called_once_with(session, self.contract["host"], "old")
        name, params = session.request.call_args.args
        self.assertEqual("tools/call", name)
        self.assertEqual("mt_apk_native_disassemble", params["name"])
        self.assertEqual("", params["arguments"]["editSessionId"])
        self.assertEqual("fresh", params["arguments"]["workspaceId"])

    def test_non_native_host_cannot_reach_disassembly(self):
        session = Mock()
        with patch("mt_systemui_probe.MtSession", return_value=session), patch("mt_systemui_probe.open_host") as host:
            for counts in [{}, {"dexEntries": 1, "classes": 0}]:
                host.return_value = {"workspaceId": "fresh", "counts": counts}
                with self.assertRaises(AuditError):
                    verify_mcp(self.image, self.contract, "测试地址")
        session.request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
