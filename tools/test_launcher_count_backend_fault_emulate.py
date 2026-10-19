"""调用点安装事务的 ARM64 故障注入执行测试。"""

import tempfile
import unittest
from pathlib import Path

from launcher_count_backend_fault_emulate import compile_harness, emulate, find_clang


class CountBackendFaultEmulationTest(unittest.TestCase):
    def test_real_arm64_transaction_code_passes_fault_cases(self):
        try:
            clang = find_clang(None)
            import unicorn  # noqa: F401
        except (RuntimeError, ImportError) as error:
            self.skipTest(str(error))
        with tempfile.TemporaryDirectory(prefix="launcher-backend-test-") as name:
            result = emulate(compile_harness(clang, Path(name)))
        self.assertEqual(7, result["场景数"])
        self.assertEqual("全部通过", result["结果"])


if __name__ == "__main__":
    unittest.main()
