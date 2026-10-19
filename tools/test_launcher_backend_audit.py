"""后端审计的输入和反例判定测试；真实留存样本必须另行显式审计。"""

import copy
import json
import struct
import unittest
from unittest.mock import patch

from launcher_backend_audit import (
    CONTRACT, COORDINATION_SYMBOLS, HOOK_IMPORTS, HOOK_REGIONS, NEAR_BRANCH_MARKERS,
    REPLACEMENT,
    THREAD_SUSPENSION_MARKERS, THREAD_SUSPENSION_SYMBOLS, audit_thread_suspension,
    audit_near_branch_plugin, direct_calls, imported_calls, mixed_jump_target,
    patch_bytes, plt_symbols, relr_addresses, verify_samples,
)
from launcher_native_audit import AuditError, ElfImage, digest
from test_launcher_native_audit import fixture


class BackendAuditTest(unittest.TestCase):
    def test_relr_address_and_bitmap(self):
        self.assertEqual({0x1000, 0x1008, 0x1018},
                         relr_addresses(struct.pack("<QQ", 0x1000, 0b1011)))

    def test_relr_empty_bitmap_still_advances_cursor(self):
        self.assertEqual({0x1000, 0x1200},
                         relr_addresses(struct.pack("<QQQ", 0x1000, 1, 3)))

    def test_relr_malformed_duplicate_or_unaligned_rejected(self):
        for raw in (b"\0", struct.pack("<Q", 3), struct.pack("<Q", 6),
                    struct.pack("<QQ", 0x1000, 0x1000),
                    struct.pack("<QQ", 0xfffffffffffffff8, 3)):
            with self.subTest(raw=raw.hex()), self.assertRaises(AuditError):
                relr_addresses(raw)

    def test_sample_shape_is_literal_at_sixteen(self):
        raw = patch_bytes()
        self.assertEqual(24, len(raw))
        self.assertEqual((0x58000091, 0xd61f0220, 0xd503201f, 0xd503201f, REPLACEMENT),
                         struct.unpack("<IIIIQ", raw))

    def test_only_complete_new_head_with_old_literal_is_witness(self):
        stock = bytes.fromhex("e20300aaa1835ff8a2031ff820f05ff8007c4cd3e10100f9")
        patch = patch_bytes()
        self.assertIsNone(mixed_jump_target(stock, patch))
        self.assertIsNone(mixed_jump_target(patch, patch))
        self.assertIsNone(mixed_jump_target(patch[:4] + stock[4:], patch))
        self.assertEqual(0xf90001e1d34c7c00, mixed_jump_target(patch[:16] + stock[16:], patch))

    def test_witness_requires_complete_window(self):
        for raw in (b"", bytes(16), bytes(25)):
            with self.assertRaises(AuditError):
                mixed_jump_target(raw, patch_bytes())

    def test_contract_cannot_grant_permission(self):
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        self.assertIs(contract["installPermitted"], False)
        for value in (True, 1, 0, None):
            changed = copy.deepcopy(contract)
            changed["installPermitted"] = value
            with self.assertRaisesRegex(AuditError, "合同不允许"):
                verify_samples(None, None, changed)

    def test_full_sample_identity_is_required(self):
        image = ElfImage(fixture())
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        with self.assertRaisesRegex(AuditError, "完整样本摘要不匹配"):
            verify_samples(image, image, contract)
        contract["images"]["framework"].update(size=len(image.data), sha256=digest(image.data))
        with self.assertRaisesRegex(AuditError, "Build ID 不匹配"):
            verify_samples(image, image, contract)

    def test_refreshing_fingerprints_cannot_skip_instruction_checks(self):
        image = ElfImage(fixture())
        contract = {"schemaVersion": 1, "installPermitted": False, "images": {},
                    "pointerSlots": {}, "branchTables": {}}
        for name in ("framework", "libc"):
            contract["images"][name] = {"size": len(image.data), "sha256": digest(image.data),
                                       "buildId": image.build_id(), "functions": []}
        with patch("launcher_backend_audit.relocations", return_value=({}, set())):
            with self.assertRaisesRegex(AuditError, "虚拟地址没有唯一"):
                verify_samples(image, image, contract)

    def test_hook_regions_do_not_overlap_and_are_ordered(self):
        # 区域按地址升序排列且两两不重叠；这里不假设它们首尾相接。
        ordered = sorted(HOOK_REGIONS)
        self.assertEqual(list(HOOK_REGIONS), ordered)
        for low, high in HOOK_REGIONS:
            self.assertLess(low, high)
        for (_, high), (next_low, _) in zip(ordered, ordered[1:]):
            self.assertLessEqual(high, next_low)

    def test_imported_call_scan_reports_sites(self):
        image = ElfImage(fixture())
        calls = direct_calls(image, 0x2000, 0x2010)
        self.assertIsInstance(calls, dict)
        with self.assertRaises(AuditError):
            imported_calls(image, 0x2000, 0x2010)

    def test_hook_import_set_has_no_coordination_and_is_precise(self):
        self.assertEqual({"sysconf", "mprotect", "__memcpy_chk"}, set(HOOK_IMPORTS))
        self.assertTrue(COORDINATION_SYMBOLS.isdisjoint(HOOK_IMPORTS))
        self.assertEqual([0xA5338], HOOK_IMPORTS["sysconf"])
        self.assertEqual([0xA536C, 0xA5394, 0xA53C4, 0xA53E8], HOOK_IMPORTS["mprotect"])
        self.assertEqual([0xA5438], HOOK_IMPORTS["__memcpy_chk"])

    def test_coordination_symbols_are_not_mistaken_for_copy_paths(self):
        # 只检查判定集合本身：拷贝/缓存类符号不能被误列成协调原语。
        self.assertNotIn("memset", COORDINATION_SYMBOLS)
        self.assertNotIn("__memcpy_chk", COORDINATION_SYMBOLS)
        self.assertNotIn("mprotect", COORDINATION_SYMBOLS)
        self.assertIn("pthread_mutex_lock", COORDINATION_SYMBOLS)
        self.assertIn("pthread_create", COORDINATION_SYMBOLS)
        self.assertIn("nanosleep", COORDINATION_SYMBOLS)

    def test_plt_mapping_rejects_non_plt_section_shape(self):
        # .plt 桩数量与 .rela.plt 不匹配时必须失败，而不是静默给出半张表。
        image = ElfImage(fixture())
        with self.assertRaises(AuditError):
            plt_symbols(image)

    def test_suspension_symbols_are_disjoint_from_hook_paths(self):
        # 挂起集合只描述「暂停执行线程」的手段，不得混入补丁/复制/缓存正常符号。
        for name in ("mprotect", "__memcpy_chk", "sysconf", "pthread_mutex_lock"):
            self.assertNotIn(name, THREAD_SUSPENSION_SYMBOLS)
        for name in ("ptrace", "tgkill", "pthread_kill", "process_vm_readv", "process_vm_writev"):
            self.assertIn(name, THREAD_SUSPENSION_SYMBOLS)
        self.assertIn(b"ptrace", THREAD_SUSPENSION_MARKERS)
        self.assertIn(b"/proc/self/task", THREAD_SUSPENSION_MARKERS)

    def test_thread_suspension_audit_detects_injected_symbol_or_marker(self):
        # 正例：注入挂起导入或标记都必须被检出，不能静默放行。
        image = ElfImage(fixture())
        with patch("launcher_backend_audit.imported_calls",
                   return_value={"ptrace": [0x2000]}):
            with self.assertRaisesRegex(AuditError, "线程挂起导入"):
                audit_thread_suspension(image)
        data = bytearray(image.data)
        data[0x600:0x606] = b"ptrace"
        with patch("launcher_backend_audit.imported_calls", return_value={}):
            with self.assertRaisesRegex(AuditError, "线程挂起标记"):
                audit_thread_suspension(ElfImage(bytes(data)))

    def test_thread_suspension_audit_requires_executable_section(self):
        image = ElfImage(fixture())
        stripped = ElfImage(bytes(image.data))
        stripped.sections = [dict(section, flags=section["flags"] & ~4)
                             for section in stripped.sections]
        with self.assertRaisesRegex(AuditError, "可执行节"):
            audit_thread_suspension(stripped)

    def test_near_branch_markers_are_not_real_hook_symbols(self):
        # 近距插件标记只描述可选插件；不得把补丁/复制/缓存正常符号混进来。
        for name in (b"mprotect", b"__memcpy_chk", b"sysconf", b"pthread_mutex_lock"):
            self.assertNotIn(name, NEAR_BRANCH_MARKERS)
        for name in (b"near_branch_trampoline",
                     b"dobby_enable_near_branch_trampoline",
                     b"forward trampoline use"):
            self.assertIn(name, NEAR_BRANCH_MARKERS)

    def test_near_branch_audit_rejects_injected_marker(self):
        # 正例：任一侧样本出现插件标记，都必须失败，不能静默放行 24 字节合同。
        clean = ElfImage(fixture())
        self.assertEqual([], audit_near_branch_plugin(clean, clean)["命中"])
        for offset in (0x600, 0x900):
            data = bytearray(clean.data)
            marker = b"dobby_enable_near_branch_trampoline"
            data[offset:offset + len(marker)] = marker
            poisoned = ElfImage(bytes(data))
            with self.subTest(offset=hex(offset)):
                with self.assertRaisesRegex(AuditError, "近距分支插件"):
                    audit_near_branch_plugin(poisoned, clean)
                with self.assertRaisesRegex(AuditError, "近距分支插件"):
                    audit_near_branch_plugin(clean, poisoned)

    def test_near_branch_audit_reports_searched_markers(self):
        clean = ElfImage(fixture())
        result = audit_near_branch_plugin(clean, clean)
        self.assertEqual([], result["命中"])
        self.assertEqual([marker.decode("ascii") for marker in NEAR_BRANCH_MARKERS],
                         result["已搜索标记"])
        self.assertIn("24", result["结论"])


if __name__ == "__main__":
    unittest.main()
