"""单分支准入、只读布局和发布生命周期的回归测试，不连接或操作设备。"""

import copy
from dataclasses import replace
import json
from pathlib import Path
import struct
import subprocess
import sys
import unittest
from types import SimpleNamespace

from launcher_native_audit import AuditError
from launcher_branch_publication_audit import (
    BRANCH_CONTRACT, Publication, Reader, eligible_pair, explore, invariant, may_release,
    transitions, verify_island, verify_layout, verify_site,
)
from launcher_shortcut_count_emulate import Arm64Object, BRANCH_SYMBOL
from test_launcher_native_audit import fixture
from launcher_native_audit import ElfImage


def branch_object():
    # 固定的未重定位机器字，不依赖本机编译产物；真实 .o 由显式审计命令核验。
    code = bytes.fromhex(
        "f045bfa9100000901002009110fedf88111600513f3e007168000054"
        "e303102aa3031ff8f045c1a83f0003eb4c0000540000001400000014")
    obj = SimpleNamespace(code=code, relocations=[
        (4, 275, "launcher_shortcut_count_state"),
        (8, 277, "launcher_shortcut_count_state"),
        (48, 282, "launcher_count_trial_keep"),
        (52, 282, "launcher_count_trial_clip"),
    ])
    obj.materialize = lambda address, externals: Arm64Object.materialize(obj, address, externals)
    return obj


class BranchEligibilityTest(unittest.TestCase):
    def test_real_conditional_branch_to_direct_branch_is_eligible(self):
        self.assertTrue(eligible_pair(0x1147054, 0x540000ac, 0x14000004, 4))

    def test_normal_instructions_and_unreviewed_branch_encodings_are_refused(self):
        for old in (0xaa0003e2, 0xf85f83a1, 0x540000bc, 0x14000004, 0x94000004, 0xd61f0200):
            with self.subTest(old=hex(old)):
                self.assertFalse(eligible_pair(0x1147054, old, 0x14000004, 4))
        for new in (0x94000004, 0xd61f0200, 0xd503201f, 0xaa0003e2):
            self.assertFalse(eligible_pair(0x1147054, 0x540000ac, new, 4))

    def test_alignment_width_and_integer_bounds_are_mandatory(self):
        for address in (-4, 2, 1 << 64, 4.0, True):
            self.assertFalse(eligible_pair(address, 0x540000ac, 0x14000004, 4))
        for size in (1, 2, 8, 24, True, 4.0):
            self.assertFalse(eligible_pair(0x1147054, 0x540000ac, 0x14000004, size))
        for word in (-1, 1 << 32, True, 0.0):
            self.assertFalse(eligible_pair(0x1147054, word, 0x14000004, 4))
            self.assertFalse(eligible_pair(0x1147054, 0x540000ac, word, 4))


class IslandContractTest(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads(BRANCH_CONTRACT.read_text(encoding="utf-8"))

    def test_reviewed_island_and_relocations(self):
        verify_island(branch_object(), self.contract)
        self.assertEqual(BRANCH_SYMBOL, self.contract["island"]["symbol"])

    def test_modified_code_or_relocations_cannot_reuse_prior_evidence(self):
        obj = branch_object()
        obj.code = b"\0\0\0\0" + obj.code[4:]
        with self.assertRaises(AuditError):
            verify_island(obj, self.contract)
        obj = branch_object()
        obj.relocations[2] = (48, 283, "launcher_count_trial_keep")
        with self.assertRaises(AuditError):
            verify_island(obj, self.contract)

    def test_contract_cannot_grant_installation_or_change_strategy(self):
        for key, value in [("installPermitted", True), ("schemaVersion", 2), ("policy", "getter-entry")]:
            changed = copy.deepcopy(self.contract)
            changed[key] = value
            with self.assertRaises(AuditError):
                verify_island(branch_object(), changed)
        for key, value in [("address", "0x114701c"), ("writeBytes", 24),
                           ("originalWord", "0xaa0003e2"), ("keepAddress", "0x1147068")]:
            changed = copy.deepcopy(self.contract)
            changed["site"][key] = value
            with self.assertRaises(AuditError):
                verify_island(branch_object(), changed)

    def test_aarch32_or_unknown_architecture_reference_is_refused(self):
        self.contract["architecture"]["section"] = "E2.2.5"
        with self.assertRaises(AuditError):
            verify_island(branch_object(), self.contract)

    def test_read_only_layout_checks_both_page_sizes_and_load_bias(self):
        for page_size in (4096, 16384):
            for bias in (0, 0x7000000000):
                self.assertEqual(0x14000000, verify_layout(
                    branch_object(), bias + 0x5000000, bias + 0x5000000 + page_size,
                    page_size, bias) & 0xfc000000)

    def test_overlapping_misaligned_unreachable_or_wrapping_layout_is_refused(self):
        for code, data, page, bias in [
            (0x5000000, 0x5000000, 4096, 0),
            (0x5000004, 0x5001000, 4096, 0),
            (0x1147000, 0x5001000, 4096, 0),
            (0x5000000, 0x1147000, 4096, 0),
            (0x5000000, 0x5001000, 8192, 0),
            (0x5000000, 0x5001000, 4096, 4),
            (0x10000000, 0x10001000, 4096, 0),
            (0x5000000, 0x1000000000, 4096, 0),
            (0x5000000, 0x5001000, 4096, (1 << 64) - 4096),
        ]:
            with self.subTest(code=code, data=data, page=page, bias=bias), self.assertRaises(AuditError):
                verify_layout(branch_object(), code, data, page, bias)

    def test_direct_branch_into_guard_is_refused(self):
        data = bytearray(fixture())
        # 小型合成执行段模拟完整宿主的分支扫描；被核对的窗口来自固定合同。
        target, source = 0x1147054, 0x2000
        struct.pack_into("<I", data, 0x400, 0x14000000 | ((target - source) // 4))
        image = ElfImage(bytes(data))
        image.code = lambda address, size: {
            0x1147048: bytes.fromhex(self.contract["site"]["guardBytes"]),
            0x114701c: bytes.fromhex("e20300aaa1835ff8a2031ff820f05ff8007c4cd3e10100f9"),
        }[address][:size]
        with self.assertRaisesRegex(AuditError, "直接分支"):
            verify_site(image, self.contract)


class PublicationModelTest(unittest.TestCase):
    def test_exhaustive_policy_covers_critical_interleavings(self):
        result = explore()
        self.assertTrue(result["通过"])
        self.assertEqual(1164, result["状态数"])
        self.assertEqual(6188, result["迁移数"])
        self.assertEqual({"先新后旧", "恢复后旧跳转", "关闭时已有调用读到旧值"}, set(result["关键交错"]))

    def test_missing_cache_sync_or_sealing_has_counterexample(self):
        result = explore("early_publish")
        self.assertFalse(result["通过"])
        self.assertIn("同步", result["原因"])
        self.assertEqual("线程0:进入曾发布的代码页", result["反例"][-1])

    def test_restoring_original_and_zero_active_calls_do_not_allow_free(self):
        result = explore("early_release")
        self.assertFalse(result["通过"])
        self.assertIn("释放", result["原因"])
        self.assertIn("错误地因无活跃调用而释放", result["反例"])
        state = Publication(stage=5, alive=True, ever_published=True, patched=False, blocked=True)
        self.assertFalse(may_release(state))
        self.assertTrue(all(r.pc == 0 for r in state.readers))

    def test_never_published_pages_may_be_released(self):
        for stage in range(1, 6):
            state = Publication(stage=stage, alive=True)
            self.assertTrue(may_release(state))
            discarded = [after for event, after in transitions(state) if event == "发布前放弃并释放"]
            self.assertEqual(1, len(discarded))
            self.assertIsNone(invariant(discarded[0]))
            self.assertFalse(discarded[0].alive)

    def test_post_publication_failure_cannot_reenable_or_rewrite_code(self):
        state = Publication(stage=5, alive=True, ever_published=True, patched=True,
                            blocked=True, value=0)
        changes = list(transitions(state))
        self.assertTrue(changes)
        for _, after in changes:
            self.assertEqual(5, after.stage)
            self.assertTrue(after.alive)
            self.assertEqual(0, after.value)
            self.assertTrue(after.blocked)

    def test_inflight_config_can_finish_but_cannot_observe_unpublished_value(self):
        state = Publication(stage=5, alive=True, ever_published=True, blocked=True,
                            value=0, history=3, readers=(Reader(2, 5, True), Reader()))
        self.assertIsNone(invariant(state))
        self.assertIsNotNone(invariant(replace(state, readers=(Reader(2, 7, True), Reader()))))
        self.assertIsNotNone(invariant(replace(state, history=1)))
        self.assertIsNotNone(invariant(replace(state, alive=False)))

    def test_help_needs_neither_host_nor_emulator(self):
        script = Path(__file__).with_name("launcher_branch_publication_audit.py")
        result = subprocess.run([sys.executable, str(script), "--help"], capture_output=True)
        self.assertEqual(0, result.returncode)
        self.assertIn(b"--object", result.stdout)


if __name__ == "__main__":
    unittest.main()
