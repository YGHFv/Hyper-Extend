import hashlib
import json
import re
import unittest
from pathlib import Path

from launcher_native_audit import AuditError, DEFAULT_CONTRACT, verify_manifest_bytes


class IdentityContractTest(unittest.TestCase):
    def test_runtime_checks_structure_without_host_version_or_digest_whitelist(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/launcherNative/java/io/github/YGHFv/HyperExtend/hook/launcher/LauncherIdentity.kt").read_text(encoding="utf-8")
        contract = json.loads(DEFAULT_CONTRACT.read_text(encoding="utf-8"))
        strings = dict(re.findall(r'const val (\w+) = "([^"]+)"', source))
        self.assertEqual(contract["host"]["packageName"], strings["PACKAGE"])
        self.assertEqual(contract["image"]["locator"], "zip_entry:" + strings["IMAGE_ENTRY"])
        self.assertEqual(contract["manifest"]["requiredRuntime"], strings["RUNTIME_LIBRARY"])
        self.assertEqual(contract["manifest"]["entryLibrary"], strings["ENTRY_LIBRARY"])
        self.assertNotRegex(source, r"const val (VERSION|IMAGE_SHA256|IMAGE_SIZE|MANIFEST_SHA256|MANIFEST_SIZE)")
        self.assertIn("source.imageCompatible(application.sourceApk)", source)

    def test_manifest_read_requires_exact_complete_bytes(self):
        expected = {"size": 4, "sha256": hashlib.sha256(b"test").hexdigest()}
        window = {"byteOffset": 0, "nextByteOffset": 4, "totalBytes": 4, "hex": "74657374"}
        self.assertEqual(4, verify_manifest_bytes({"byteWindow": window}, expected))
        for key, value in [("byteOffset", 1), ("nextByteOffset", 3), ("totalBytes", 5),
                           ("hex", "746573"), ("hex", "74657375")]:
            with self.subTest(key=key), self.assertRaises(AuditError):
                verify_manifest_bytes({"byteWindow": dict(window, **{key: value})}, expected)
        with self.assertRaises(AuditError):
            verify_manifest_bytes({"byteWindow": window, "pagination": {"hasMore": True}}, expected)
        with self.assertRaises(AuditError):
            verify_manifest_bytes({"byteWindow": window, "truncated": True}, expected)


if __name__ == "__main__":
    unittest.main()
