"""单字提交故障对照审计器的合成用例。

真实工具链输出只由审计命令显式验证，不以跳过代替通过。这里用合成合同与替身
检查「预期拒绝」的判定逻辑本身：关键字不匹配时必须失败，而不是把任意异常都当成通过。
"""

import copy
import unittest

from launcher_native_audit import AuditError
from launcher_singleword_commit_fault_audit import (POLICY, expect_accepted,
                                                   expect_rejected, verify_contract)


def contract():
    return {
        "schemaVersion": 1,
        "policy": POLICY,
        "installPermitted": False,
        "toolchain": {
            "clang": "Android clang version 99.0.0",
            "flags": "--target=aarch64-linux-android29 -O3 -std=c++17 -c -Wall -Wextra "
                     "-Werror -fno-exceptions -fno-rtti -fvisibility=hidden -mno-outline-atomics",
        },
        "functions": {
            "faults_two_word_store": {"label": "两条存储", "subject": "commit",
                                      "keyword": "恰好两条", "instructions": 3},
            "faults_pair_store": {"label": "STP", "subject": "commit",
                                  "keyword": "不是单条 32 位存储", "instructions": 2},
            "faults_sync_missing_ic": {"label": "缺 ic", "subject": "sync",
                                       "keyword": "缺少 ic ivau", "instructions": 4},
            "faults_commit_first": {"label": "先提交", "subject": "order",
                                    "keyword": "先缓存同步", "instructions": 6},
        },
        "counts": {"faults": 4},
        "notClosedGates": ["门槛5：不关闭门槛 5。"],
    }


class ContractTest(unittest.TestCase):
    def test_valid_contract_passes(self):
        result = verify_contract(contract())
        self.assertEqual(4, len(result))

    def test_install_permitted_is_rejected(self):
        broken = contract()
        broken["installPermitted"] = True
        with self.assertRaisesRegex(AuditError, "安装许可"):
            verify_contract(broken)

    def test_missing_gate_is_rejected(self):
        broken = contract()
        broken["notClosedGates"] = ["门槛3：x"]
        with self.assertRaisesRegex(AuditError, "门槛 5"):
            verify_contract(broken)

    def test_count_mismatch_is_rejected(self):
        broken = contract()
        broken["counts"]["faults"] = 3
        with self.assertRaisesRegex(AuditError, "数量与合同不符"):
            verify_contract(broken)

    def test_subject_coverage_is_required(self):
        broken = contract()
        for spec in broken["functions"].values():
            spec["subject"] = "commit"
        with self.assertRaisesRegex(AuditError, "覆盖提交、同步、顺序"):
            verify_contract(broken)

    def test_recorded_flags_must_match_the_toolchain_flags(self):
        broken = contract()
        broken["toolchain"]["flags"] = "-O0"
        with self.assertRaisesRegex(AuditError, "编译参数"):
            verify_contract(broken)

    def test_recorded_clang_version_is_required(self):
        broken = contract()
        broken["toolchain"]["clang"] = "未知编译器"
        with self.assertRaisesRegex(AuditError, "clang 版本"):
            verify_contract(broken)

    def test_wrong_policy_is_rejected(self):
        broken = contract()
        broken["policy"] = "other"
        with self.assertRaisesRegex(AuditError, "策略"):
            verify_contract(broken)


class ExpectationTest(unittest.TestCase):
    def test_expected_rejection_passes_when_keyword_matches(self):
        result = expect_rejected(lambda: _raise("必须恰好两条指令，实际 3 条"),
                                 "commit", "两条存储", "恰好两条")
        self.assertEqual("commit", result["判定"])

    def test_expected_rejection_fails_when_keyword_absent(self):
        with self.assertRaisesRegex(AuditError, "原因不是预期"):
            expect_rejected(lambda: _raise("完全是别的原因"), "commit", "两条存储", "恰好两条")

    def test_expected_rejection_fails_when_nothing_is_raised(self):
        with self.assertRaisesRegex(AuditError, "未被拒绝"):
            expect_rejected(lambda: {"通过": True}, "commit", "两条存储", "恰好两条")

    def test_expected_acceptance_returns_label(self):
        self.assertEqual("正常", expect_accepted(lambda: None, "正常"))


def _raise(message):
    raise AuditError(message)


if __name__ == "__main__":
    unittest.main()
