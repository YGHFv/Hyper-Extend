"""首次启用自动初始化，以及真实宿主路径、机器字和批处理回归。"""
import json
import re
import struct
import unittest
import subprocess
from launcher_native_audit import ElfImage
from launcher_diagnostic_emulate import trial_entry

import test_launcher_directory_policy as directory_test
from launcher_diagnostic_emulate import run_case

ROOT = directory_test.ROOT

class FirstStartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory_test.DirectoryPolicyTest.setUpClass.__func__(cls)

    compile = directory_test.DirectoryPolicyTest.compile

    def test_automatic_first_start_and_faults(self):
        cases = {i: f"首次启动场景 {i}" for i in range(28)}
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_first_start_trial.cpp",
                                    "first-start", "launcher_first_start_trial")
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)

    def test_host_identity_wait_and_batch_phases(self):
        cases = {i: f"宿主挂载场景 {i}" for i in range(22)}
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_host_runtime_trial.cpp",
                                    "host-runtime", "launcher_host_runtime_trial")
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)

    def test_runtime_parameter_window_matches_evidence_without_build_id_gate(self):
        contract = json.loads((ROOT / "app/src/test/resources/launcher-shortcut-native-contract.json")
                              .read_text(encoding="utf-8"))
        source = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
        points = (ROOT / "app/src/main/cpp/launcher/hook_points.h").read_text(encoding="utf-8")
        window = re.search(r"kCount\[\]\s*=\s*\{(.*?)\};", points, re.S)[1]
        words = [int(value, 16) for value in re.findall(r"\{0x([0-9a-fA-F]+)", window)][6:]
        self.assertEqual(bytes.fromhex(contract["windows"][0]["bytes"]),
                         b"".join(struct.pack("<I", word) for word in words))
        header = (ROOT / "app/src/main/cpp/launcher/host_image_contract.h").read_text(encoding="utf-8")
        self.assertNotIn("kLauncherBuildId", header)
        self.assertIn("launcher::points::resolve", source)
        self.assertIn("launcher::apply_unique_batch(unique,", source)
        ui = (ROOT / "app/src/main/java/io/github/YGHFv/HyperExtend/ui/SettingsActivity.kt").read_text(encoding="utf-8")
        self.assertNotIn('title = "初始化桌面安全状态"', ui)

    def test_runtime_mapping_requires_complete_elf_evidence(self):
        output = self.base / "mapping.so"
        result = subprocess.run([
            str(self.clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
            "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
            "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
            "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
            "-ffunction-sections", "-fdata-sections", "-shared", "-nostdlib",
            "-Wl,-Bsymbolic", "-Wl,--no-undefined", "-Wl,--gc-sections",
            str(ROOT / "app/src/test/cpp/launcher_runtime_mapping_trial.cpp"), "-o", str(output),
        ], capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))
        image = ElfImage(output.read_bytes())
        entry = trial_entry(image, "launcher_runtime_mapping_trial")
        cases = {i: f"映像映射场景 {i}" for i in range(11)}
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)


if __name__ == "__main__":
    unittest.main()
