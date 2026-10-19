import copy
import struct
import unittest

from launcher_native_audit import AuditError, ElfImage, digest, verify_startup_contract
from test_launcher_native_package_audit import library


class StartupAuditTest(unittest.TestCase):
    def setUp(self):
        self.data = bytearray(library(exports={"app_entry_point"}, imports=set()))
        struct.pack_into("<Q", self.data, 64 + 56 + 32, 0x400)
        struct.pack_into("<Q", self.data, 64 + 56 + 40, 0x400)
        struct.pack_into("<Q", self.data, 0x618 + 16, 960)
        for offset, raw in [(0x18, "f30308aa"), (0x278, "680200a9"), (0x288, "680a00f9"),
                            (0x290, "f44f48a9f65747a9f85f46a9fd7b45a9ff430291c0035fd6")]:
            self.data[0x400 + offset:0x400 + offset + len(bytes.fromhex(raw))] = bytes.fromhex(raw)
        self.contract = {"image": {"size": len(self.data), "sha256": digest(self.data)},
                         "functions": [{"name": "app_entry_point", "address": "0x2000", "size": 960,
                                        "sha256": digest(self.data[0x400:0x7c0])}]}

    def verify(self, refresh_hashes=False):
        if refresh_hashes:
            self.contract["image"]["sha256"] = digest(self.data)
            self.contract["functions"][0]["sha256"] = digest(self.data[0x400:0x7c0])
        return verify_startup_contract(ElfImage(bytes(self.data)), self.contract)

    def test_observation_is_not_loader_or_ownership_proof(self):
        result = self.verify()
        self.assertFalse(result["加载路径已证明"])
        self.assertEqual(960, result["字节数"])

    def test_each_fingerprint_and_range_must_match(self):
        original = copy.deepcopy(self.contract)
        for section, key, value in [("image", "size", 1), ("image", "sha256", "0" * 64),
                                     ("functions", "address", "0x2004"), ("functions", "size", 956),
                                     ("functions", "name", "JNI_OnLoad"), ("functions", "sha256", "0" * 64)]:
            self.contract = copy.deepcopy(original)
            target = self.contract[section] if section == "image" else self.contract[section][0]
            target[key] = value
            with self.assertRaises(AuditError):
                self.verify()

    def test_abi_words_cannot_be_accepted_by_merely_refreshing_digest(self):
        original = bytes(self.data)
        for offset in [0x18, 0x278, 0x288, 0x290, 0x2a4]:
            self.data = bytearray(original)
            struct.pack_into("<I", self.data, 0x400 + offset, 0xd503201f)
            with self.assertRaises(AuditError):
                self.verify(refresh_hashes=True)

    def test_symbol_kind_visibility_and_undefined_entry_are_rejected(self):
        original = bytes(self.data)
        for offset, value, fmt in [(0x61c, 0x11, "<B"), (0x61c, 0x22, "<B"),
                                    (0x61d, 2, "<B"), (0x61e, 0, "<H"), (0x61e, 65535, "<H")]:
            self.data = bytearray(original)
            struct.pack_into(fmt, self.data, offset, value)
            with self.assertRaises(AuditError):
                self.verify(refresh_hashes=True)

    def test_duplicate_entry_and_unreviewed_jni_definition_are_rejected(self):
        original = bytes(self.data)
        for add_jni in [False, True]:
            self.data = bytearray(original)
            self.data[0x630:0x648] = self.data[0x618:0x630]
            struct.pack_into("<Q", self.data, 0x1800 + 3 * 64 + 32, 72)
            if add_jni:
                offset = 1 + len("app_entry_point") + 1
                self.data[0x1000 + offset:0x1000 + offset + 11] = b"JNI_OnLoad\0"
                struct.pack_into("<Q", self.data, 0x1800 + 2 * 64 + 32, offset + 11)
                struct.pack_into("<I", self.data, 0x630, offset)
            with self.assertRaises(AuditError):
                self.verify(refresh_hashes=True)

    def test_dynamic_symbol_table_shape_and_link_are_checked(self):
        original = bytes(self.data)
        for offset, value, fmt in [(4, 2, "<I"), (32, 47, "<Q"), (40, 65535, "<I"),
                                    (40, 4, "<I"), (56, 16, "<Q")]:
            self.data = bytearray(original)
            struct.pack_into(fmt, self.data, 0x1800 + 3 * 64 + offset, value)
            with self.assertRaises(AuditError):
                self.verify(refresh_hashes=True)


if __name__ == "__main__":
    unittest.main()
