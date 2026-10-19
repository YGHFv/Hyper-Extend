"""执行实验日志替身，验证默认关闭、定长格式与 API 缺失回退。"""

import subprocess
import tempfile
import unittest
from pathlib import Path

from launcher_diagnostic_emulate import run_case, trial_entry
from launcher_native_audit import ElfImage
from launcher_safety_parity_audit import find_clang


ROOT = Path(__file__).resolve().parents[1]
CASES = {0: "直达日志", 1: "缺少 API 回退", 2: "零值", 3: "最大值",
         4: "空阶段拒绝", 5: "超长阶段拒绝", 6: "最大阶段长度"}


class EntryTraceTest(unittest.TestCase):
    def verify(self, enabled):
        clang = find_clang(None, ROOT)
        self.assertIsNotNone(clang, "需要本机 NDK 执行 ARM64 日志替身")
        with tempfile.TemporaryDirectory(prefix="launcher-entry-trace-") as temporary:
            output = Path(temporary) / "trace.so"
            command = [
                str(clang), "--target=aarch64-linux-android26", "-std=c++17", "-O1",
                "-Wall", "-Wextra", "-Werror", "-fno-exceptions", "-fno-rtti",
                "-fno-stack-protector", "-fvisibility=hidden", "-mno-outline-atomics",
                "-fno-builtin", "-U_FORTIFY_SOURCE", "-D_FORTIFY_SOURCE=0",
                "-shared", "-nostdlib", "-Wl,-Bsymbolic", "-Wl,--no-undefined",
            ]
            if enabled:
                command.append("-DLAUNCHER_NATIVE_EXPERIMENTAL_INSTALL=1")
            command += [str(ROOT / "app/src/test/cpp/launcher_entry_trace_trial.cpp"),
                        "-o", str(output)]
            built = subprocess.run(command, capture_output=True)
            self.assertEqual(0, built.returncode, built.stderr.decode("utf-8", errors="replace"))
            image = ElfImage(output.read_bytes())
            entry = trial_entry(image, "launcher_entry_trace_trial")
            for scenario, label in CASES.items():
                with self.subTest(scenario=label, enabled=enabled):
                    run_case(image, entry, scenario, cases=CASES)

    def test_experimental_trace(self):
        self.verify(True)

    def test_default_build_never_resolves_or_writes_logs(self):
        self.verify(False)


if __name__ == "__main__":
    unittest.main()
