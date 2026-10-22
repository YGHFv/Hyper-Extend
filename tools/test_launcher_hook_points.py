"""不依赖私有 APK 的 Hook 参数、调用关系、唯一性和无版本门禁回归。"""
import re
import struct
import tempfile
import unittest
from pathlib import Path

from launcher_hook_points_audit import compile_resolver, resolve, NAMES, ROOT
from launcher_native_audit import ElfImage


def fixture(shift=0):
    raw = bytearray(0xD000)
    raw[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQQIHHHHHH", raw, 16, 3, 183, 1, 0, 64, 0xC100, 0, 64, 56, 2, 64, 2, 1)
    struct.pack_into("<IIQQQQQQ", raw, 64, 1, 4, 0, 0, 0, 0x1000, 0x1000, 4096)
    struct.pack_into("<IIQQQQQQ", raw, 120, 1, 5, 0x1000, 0x1000, 0, 0xB000, 0xB000, 4096)
    raw[0xC000:0xC00B] = b"\0.shstrtab\0"
    struct.pack_into("<IIQQQQIIQQ", raw, 0xC140, 1, 3, 0, 0, 0xC000, 11, 0, 0, 1, 0)
    sites = {name: value + shift for name, value in {
        "count": 0x1100, "getter": 0x1300, "share": 0x1500, "small": 0x1900,
        "share_valid": 0x2100, "small_valid": 0x2500, "init": 0x3100,
        "return": 0x3300, "primary": 0x3500, "secondary": 0x3600, "caller": 0x3700,
        "primary_entry": 0x3800, "secondary_entry": 0x3900,
    }.items()}

    def words(at, *values):
        struct.pack_into("<" + "I" * len(values), raw, at, *values)

    def branch(at, target, link=True):
        words(at, (0x94000000 if link else 0x14000000) | (((target - at) // 4) & 0x03ffffff))

    words(sites["count"], 0xa9bf79fd, 0xaa0f03fd, 0xd10241ef, 0xaa0103e0, 0xf81f83a1,
          0, 0xaa0003e2, 0xf85f83a1, 0xf81f03a2, 0xf85ff020, 0xd34c7c00, 0xf90001e1,
          0x93417c01, 0xf85f03a3, 0xeb03003f, 0xd65f03c0)
    branch(sites["count"] + 20, sites["getter"])
    words(sites["getter"], 0xa9bf79fd, 0xaa0f03fd, 0xd10021ef, 0xf9403f40,
          0xd2800080, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0)
    words(sites["share"], 0xa9bf79fd, 0xaa0f03fd, 0xd10041ef, 0xaa0103e0, 0xaa0203e3,
          0xf81f83a2, 0xaa1603e1, 0x94000000, 0xaa0003e1, 0xf85f83a3,
          0x91400362, 0xf9400042, 0xf81f03a0, 0x94000000, 0xf85f03a1, 0xf85f83a3,
          0x91400362, 0xf9400042)
    words(sites["small"], 0xa9bf79fd, 0xaa0f03fd, 0xd100a1ef, 0xf81f83b6,
          0xf81f03a1, 0xf81e83a2, 0xf9400360, 0x94000000, 0xf85e83a0,
          0xb841f002, 0x8b1c8042, 0xf81d83a2, 0x6b16005f, 0xd2a20400, 0xf800f020)
    words(sites["share_valid"], 0xa9bf79fd, 0xaa0f03fd, 0xd10401ef, 0xf81f83b6,
          0xf81283a1, 0xf81203a2, 0xf81183a3, 0xf81103a5, 0xd2800041, 0x94000000,
          0xaa0003e1, 0xf85183a0, 0xf81083a1, 0xb800f020, 0xf9400360, 0,
          0xf85083a0, 0x9100c2c0)
    branch(sites["share_valid"] + 60, sites["init"])
    branch(sites["share_valid"] + 72, sites["return"], False)
    words(sites["share_valid"] + 80, 0x9100c2c0)
    branch(sites["share_valid"] + 84, sites["return"], False)
    words(sites["share_valid"] + 92, 0x9100c2c0)
    branch(sites["share_valid"] + 96, sites["return"], False)
    words(sites["small_valid"], 0xa9bf79fd, 0xaa0f03fd, 0xd10101ef, 0xf81f83b6,
          0xf81f03a1, 0xaa0203f0, 0xaa0103e2, 0xaa1003e1, 0xf81e83a1, 0xf81e03a3,
          0xf81d83a5, 0xf9400360, 0, 0xf85e83a1, 0x9100c2c0, 0,
          0xf85f03a1, 0xf85e83a2, 0xf85e03a3, 0x94000000, 0x14000000)
    branch(sites["small_valid"] + 48, sites["init"])
    branch(sites["small_valid"] + 60, sites["return"], False)
    words(sites["init"], 0xa9bf79fd, 0xaa0f03fd, 0xf9402b64, 0xf81f8de0, 0xf9430b5e,
          0xd63f03c0, 0xaa1d03ef, 0xa8c179fd, 0xf81f83a0, 0xd65f03c0)
    words(sites["return"], 0xf85f83a2, 0xaa1d03ef, 0xa8c179fd, 0xa9bf79fd, 0xaa0f03fd,
          0xa9bf09e0, 0xf9431b5e, 0xd63f03c0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0)
    for name in ["primary", "secondary"]:
        at = sites[name]
        words(at - 12, 0xb840f020, 0x8b1c8000, 0xaa0003e1)
        branch(at, sites["share"])
    branch(sites["primary"] + 4, sites["primary"] + 32, False)
    words(sites["secondary"] + 4, 0xaa1603e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0)
    words(sites["caller"] - 8, 0xf85903a1, 0xaa0003e2)
    branch(sites["caller"], sites["small"])
    words(sites["caller"] + 4, 0xaa0003e1, 0xf81883a1, 0x94000000)
    # 入口解析以真实匿名闭包的 15 条稳定前缀为合同；主入口的免责声明位与
    # 次入口的字段偏移都由解析器再次核对。
    words(sites["primary_entry"], 0xa9bf79fd, 0xaa0f03fd, 0xf9400fa0,
          0xb8417001, 0x8b1c8021, 0xf9400ba0, 0x372002a0,
          0xb8417020, 0x8b1c8000, 0xb8417002, 0x8b1c8042,
          0x6b16005f, 0x54000061, 0xaa1603e0, 0x14000003,
          0xb8407040)
    words(sites["secondary_entry"], 0xa9bf79fd, 0xaa0f03fd, 0xf9400ba0,
          0xb8417001, 0x8b1c8021, 0xb8413020, 0x8b1c8000, 0xb8417002,
          0x8b1c8042, 0x6b16005f, 0x54000061, 0xaa1603e0,
          0x14000003, 0xb8407040, 0x8b1c8000)
    return raw, sites


class HookPointsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="launcher-points-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.trial = compile_resolver(cls.temp.name)

    def check(self, raw):
        return resolve(self.trial, ElfImage(bytes(raw)))

    def test_same_rules_resolve_relocated_code_without_a_version_table(self):
        for shift in [0, 0x100, 0x824]:
            with self.subTest(shift=shift):
                raw, sites = fixture(shift)
                result = self.check(raw)
                self.assertEqual(0xEB, result["mask"])
                # 解析器写入的是宿主映像内的绝对地址；Unicorn 把样本加载到
                # 0x1000000，因此高位来自映射基址，而不是 fixture 的 shift。
                expected = [sites["count"] + 20 + 0x10000000,
                            sites["primary"] + 0x10000000,
                            sites["secondary"] + 0x10000000,
                            sites["caller"] + 0x10000000,
                            sites["share_valid"] + 0x10000000,
                            sites["small_valid"] + 0x10000000]
                self.assertEqual([hex(a) for a in expected],
                                 [result["points"][n]["address"] for n in NAMES])

    def test_audit_reports_original_words_not_neighboring_addresses(self):
        raw, sites = fixture()
        result = self.check(raw)
        indexes = (sites["count"] + 20, sites["primary"], sites["secondary"],
                   sites["caller"], sites["share_valid"], sites["small_valid"])
        expected_words = [struct.unpack_from("<I", raw, address)[0] for address in indexes]
        self.assertEqual([hex(word) for word in expected_words],
                         [result["points"][name]["word"] for name in NAMES])

    def test_non_code_identity_bytes_do_not_gate_compatibility(self):
        raw, _ = fixture()
        raw[0x400:0x600] = bytes(range(256)) * 2
        self.assertEqual(0xEB, self.check(raw)["mask"])

    def test_share_validity_matches_enter_production_mask(self):
        for word in (0xf9400360, 0xf9565360):
            with self.subTest(word=hex(word)):
                raw, sites = fixture()
                struct.pack_into("<I", raw, sites["share_valid"] + 56, word)
                self.assertEqual(0xEB, self.check(raw)["mask"])

    def test_old_closure_entries_are_diagnostic_but_validity_frames_are_published(self):
        raw, sites = fixture()
        result = self.check(raw)
        self.assertEqual(0xEB, result["mask"])

    def test_async_false_result_exits_are_collected_by_return_thunk(self):
        raw, sites = fixture()
        result = self.check(raw)
        self.assertEqual([
            {"address": hex(sites["share_valid"] + offset + 0x10000000),
             "word": "0x9100c2c0"}
            for offset in (68, 80, 92)
        ], result["shareValidityExits"])
        self.assertEqual([
            {"address": hex(sites["small_valid"] + 56 + 0x10000000),
             "word": "0x9100c2c0"}
        ], result["freeformValidityExits"])

    def test_validity_register_changes_do_not_affect_action_points(self):
        raw, sites = fixture()
        struct.pack_into("<I", raw, sites["share_valid"] + 28, 0xf81103a4)
        result = self.check(raw)
        self.assertEqual(0xAB, result["mask"])

    def test_changed_parameters_and_result_encoding_reject_affected_point(self):
        for name, offset, word, mask in [
            ("count", 16, 0xf81f83a2, 1), ("getter", 16, 0x910082c0, 1),
            ("count", 52, 0xf85f03a4, 1), ("primary", -4, 0xaa0003e2, 2),
            ("secondary", -4, 0xaa0003e2, 4),
            ("small", 32, 0xb841f003, 32),
            ("share_valid", 0, 0xa9bf79fe, 64),
            ("small_valid", 0, 0xa9bf79fe, 128),
        ]:
            with self.subTest(name=name, offset=offset):
                raw, sites = fixture()
                struct.pack_into("<I", raw, sites[name] + offset, word)
                self.assertEqual(0, self.check(raw)["mask"] & mask)

    def test_duplicate_function_and_extra_caller_are_not_first_match_wins(self):
        raw, sites = fixture()
        raw[0x3b00:0x3b48] = raw[sites["share"]:sites["share"] + 72]
        self.assertEqual(1, self.check(raw)["mask"] & 0x01)
        self.assertEqual(0, self.check(raw)["mask"] & 0x02)
        self.assertNotEqual(0, self.check(raw)["mask"] & 0x20)
        raw, sites = fixture()
        struct.pack_into("<I", raw, 0x3b00, 0x94000000 | (((sites["getter"] - 0x3b00) // 4) & 0x03ffffff))
        self.assertEqual(0, self.check(raw)["mask"] & 0x01)

    def test_changed_call_target_and_writable_code_are_rejected(self):
        raw, sites = fixture()
        struct.pack_into("<I", raw, sites["count"] + 20, 0x94000000)
        self.assertEqual(0, self.check(raw)["mask"] & 1)
        raw, _ = fixture()
        struct.pack_into("<I", raw, 124, 7)
        self.assertEqual(0, self.check(raw)["mask"])

    def test_production_does_not_contain_host_version_or_fingerprint_gates(self):
        sources = [ROOT / "app/src/main/cpp/launcher" / name for name in
                   ["native_entry.cpp", "hook_points.h", "count_backend.cpp", "host_image_contract.h"]]
        for path in sources:
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("launcher_build_id_matches", text)
            self.assertNotRegex(text, r"0x(?:1147018|17fd75c|1b2f770|1acbed0)")
        identity = (ROOT / "app/src/launcherNative/java/io/github/YGHFv/HyperExtend/hook/launcher/LauncherIdentity.kt").read_text(encoding="utf-8")
        self.assertNotRegex(identity, r"const val (?:VERSION|IMAGE_SHA256|MANIFEST_SHA256)")


if __name__ == "__main__":
    unittest.main()
