"""主动重启免计数、真实异常熔断与显式恢复的生产路径回归。"""
import unittest

import test_launcher_directory_policy as directory_test
from launcher_diagnostic_emulate import run_case

ROOT = directory_test.ROOT

class PlannedRestartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory_test.DirectoryPolicyTest.setUpClass.__func__(cls)

    compile = directory_test.DirectoryPolicyTest.compile

    def test_transaction_preserves_fuse_and_exempts_only_verified_restarts(self):
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_planned_restart_trial.cpp",
                                    "planned-restart", "launcher_planned_restart_trial")
        cases = {i: f"主动重启事务场景 {i}" for i in range(21)}
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)

    def test_real_binder_entry_binds_operation_pid_uid_and_snapshot_token(self):
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_restart_entry_trial.cpp",
                                    "restart-entry", "launcher_restart_entry_trial")
        cases = {i: f"可信重启入口场景 {i}" for i in range(14)}
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)

    def test_root_request_consumes_only_root_owned_readonly_record_not_fuse(self):
        image, entry = self.compile(ROOT / "app/src/test/cpp/launcher_root_request_trial.cpp",
                                    "root-request", "launcher_root_request_trial")
        cases = {i: f"root 请求场景 {i}" for i in range(21)}
        for code in cases:
            with self.subTest(code=code):
                run_case(image, entry, code, cases)


if __name__ == "__main__":
    unittest.main()
