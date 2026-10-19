"""诊断模拟器输入边界；入口行为由显式 ARM64 离线试验执行。"""

import struct
import subprocess
import shutil
import tempfile
import unittest
from pathlib import Path

from launcher_diagnostic_emulate import CASES, ENTRY, run_case, trial_entry
from launcher_native_audit import AuditError, ElfImage
from launcher_safety_parity_audit import find_clang
from test_launcher_native_audit import fixture


def trial_image(duplicate=False, dependency=False):
    data = bytearray(fixture(duplicate=duplicate))
    names = b"\0" + ENTRY.encode() + b"\0"
    data[0x580:0x580 + len(names)] = names
    struct.pack_into("<Q", data, 0x1800 + 2 * 64 + 32, len(names))
    if not dependency:
        struct.pack_into("<qQ", data, 0x680, 0, 0)
    return data


class DiagnosticEmulationTest(unittest.TestCase):
    def compile_trial(self, source, target):
        root = Path(__file__).resolve().parents[1]
        clang = find_clang(None, root)
        if clang is None:
            self.skipTest("缺少本机 NDK，不能执行生产入口；不算验证通过")
        command = [
            str(clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
            "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
            "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
            "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
            "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
            str(source), "-o", str(target),
        ]
        build = subprocess.run(command, capture_output=True, check=False)
        self.assertEqual(0, build.returncode, build.stderr.decode("utf-8", errors="replace"))
        image = ElfImage(target.read_bytes())
        return image, trial_entry(image)

    def test_fault_cases_are_not_silently_removed(self):
        self.assertEqual(set(range(77)) - {29}, set(CASES))
        self.assertEqual(76, len(set(CASES.values())))

    def test_only_standalone_trial_is_accepted(self):
        self.assertEqual(0x2000, trial_entry(ElfImage(bytes(trial_image()))))

    def test_missing_or_duplicate_entry_is_rejected(self):
        for raw in (fixture(), trial_image(duplicate=True)):
            with self.assertRaises(AuditError):
                trial_entry(ElfImage(bytes(raw)))

    def test_real_system_dependency_is_rejected(self):
        with self.assertRaises(AuditError):
            trial_entry(ElfImage(bytes(trial_image(dependency=True))))

    def test_constructor_and_non_executable_entry_are_rejected(self):
        data = trial_image()
        start = data.index(b".note.gnu.build-id\0")
        data[start:start + 12] = b".init_array\0"
        with self.assertRaises(AuditError):
            trial_entry(ElfImage(bytes(data)))
        data = trial_image()
        struct.pack_into("<Q", data, 0x618 + 8, 0x200)
        with self.assertRaises(AuditError):
            trial_entry(ElfImage(bytes(data)))

    def test_trial_and_shortcut_object_are_not_linked_into_prototype(self):
        root = Path(__file__).resolve().parents[1]
        cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        self.assertNotIn("launcher_diagnostic_entry_trial", cmake)
        self.assertNotIn("launcher_count_branch_trial", cmake)
        # 生产 SO 已经链入真实数量蹦床；代码页发布后端仍未接入，阶段锁仍保证
        # 唯一安装点不可达。试验本身不应进入 CMake。
        self.assertIn("$<TARGET_OBJECTS:launcher_shortcut_trampoline>", cmake)
        self.assertIn("launcher/native_entry.cpp", cmake)
        self.assertIn("launcher/safety_startup.cpp", cmake)
        self.assertIn("launcher/count_backend.cpp", cmake)
        self.assertIn("launcher/singleword_publish.cpp", cmake)

    def test_real_production_entry_in_offline_arm64_emulator(self):
        """每次重编译直接包含生产入口的试验，不接受旧 ELF 或合成成功报告。"""
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="launcher-safety-entry-") as temporary:
            target = Path(temporary) / "trial.so"
            image, entry = self.compile_trial(
                root / "app/src/test/cpp/launcher_diagnostic_entry_trial.cpp", target)
            for case, name in CASES.items():
                with self.subTest(case=case, name=name):
                    run_case(image, entry, case)

    def test_recovery_gate_bypass_is_caught_by_executed_entry(self):
        """撤掉真实入口的用途门禁，必须编译成功后被运行断言检出。"""
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory(prefix="launcher-safety-entry-mutation-") as temporary:
            base = Path(temporary)
            native = base / "app/src/main/cpp/launcher"
            tests = base / "app/src/test/cpp"
            native.mkdir(parents=True)
            tests.mkdir(parents=True)
            for header in (root / "app/src/main/cpp/launcher").glob("*.h"):
                shutil.copyfile(header, native / header.name)
            trial = tests / "launcher_diagnostic_entry_trial.cpp"
            shutil.copyfile(root / "app/src/test/cpp/launcher_diagnostic_entry_trial.cpp", trial)
            source = (root / "app/src/main/cpp/launcher/native_entry.cpp").read_text(encoding="utf-8")
            before = "launcher::ordinary_snapshot_errors("
            self.assertEqual(3, source.count(before))
            (native / "native_entry.cpp").write_text(
                source.replace(before, "launcher::snapshot_errors("), encoding="utf-8")
            image, entry = self.compile_trial(trial, base / "mutated.so")
            for case in (42, 71, 72, 73):
                with self.subTest(case=case), self.assertRaisesRegex(AuditError, "失败"):
                    run_case(image, entry, case)


if __name__ == "__main__":
    unittest.main()
