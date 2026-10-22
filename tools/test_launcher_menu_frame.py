"""菜单真实动作蹦床必须保留现场并在 ACK 后按宿主约定续接。"""
import struct
import tempfile
import unittest
from pathlib import Path

from launcher_native_audit import ElfImage
from launcher_menu_frame_audit import (
    compile_menu, run_share_entry_frame, run_share_trace_frame, run_validity_exit_frame,
    run_validity_frame,
)
from test_launcher_hook_points import fixture
from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

ROOT = Path(__file__).resolve().parents[1]


def write_share_wrapper_args(raw, sites):
    """把真实宿主两个分享包装在 BL shareApk 前的参数搬运写进夹具。

    两个调用点都在读包名后执行 `mov x1, x0`，再把 x1 交给 shareApk；
    x2 是 shareApk 的附加参数，不是本次桥接的包名来源。
    """
    def words(at, *values):
        struct.pack_into("<" + "I" * len(values), raw, at, *values)

    for name in ("primary", "secondary"):
        words(sites[name] - 12, 0xB840F020, 0x8B1C8000, 0xAA0003E1)


class MenuFrameTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="launcher-menu-frame-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.menu = compile_menu(cls.temp.name)

    def test_share_trace_preserves_all_dart_registers(self):
        raw, _ = fixture()
        host = ElfImage(bytes(raw))
        for primary in [True, False]:
            with self.subTest(entry="主" if primary else "次", path="ACK"):
                run_share_trace_frame(self.menu, host, primary)
            with self.subTest(entry="主" if primary else "次", path="桥失败"):
                run_share_trace_frame(self.menu, host, primary, bridge_result=5)

    def test_share_wrapper_fixture_uses_x1_as_package_argument(self):
        raw, sites = fixture()
        write_share_wrapper_args(raw, sites)
        for name in ("primary", "secondary"):
            at = sites[name]
            self.assertEqual([0xB840F020, 0x8B1C8000, 0xAA0003E1],
                             list(struct.unpack_from("<III", raw, at - 12)))
            self.assertEqual(0x94000000 | (((sites["share"] - at) // 4) & 0x03FFFFFF),
                             struct.unpack_from("<I", raw, at)[0])
            self.assertEqual(0xAA0003E1, struct.unpack_from("<I", raw, at - 4)[0])

    def test_production_install_patches_async_false_result_exits(self):
        source = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(
            encoding="utf-8")
        body = source.split("int install_launcher_batch(", 1)[1].split(
            "// 受控安装计划的解析", 1)[0]
        self.assertIn("g_share_valid_specs", body)
        self.assertIn("g_freeform_valid_specs", body)
        self.assertIn("launcher_menu_share_valid_exit", body)
        self.assertIn("launcher_menu_freeform_valid_exit", body)
        self.assertIn("kShareValidity", body)
        self.assertIn("kFreeformValidity", body)

    def test_validity_feature_state_is_only_published_after_verified_install(self):
        source = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(
            encoding="utf-8")
        body = source.split("uint32_t publish_installed_feature_state(", 1)[1].split(
            "bool prepare_specs(", 1)[0]
        self.assertIn("share_validity_bit", body)
        self.assertIn("freeform_validity_bit", body)
        self.assertIn("share_bit | share_validity_bit", body)
        self.assertIn("freeform_bit | freeform_validity_bit", body)
        self.assertIn("(installed & count_bit) == count_bit", body)
        self.assertIn("launcher_menu_feature_state", body)
        self.assertNotIn("launcher_menu_feature_state = 0x", source)

    def test_failed_batch_still_publishes_other_complete_features(self):
        source = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(
            encoding="utf-8")
        failure = source.split("if (hook_result != 0 ||", 1)[1].split(
            "return kPreflightInstallDenied;", 1)[0]
        self.assertIn("publish_installed_feature_state(snapshot)", failure)
        self.assertIn("launcher::finish_install(&g_hook_install_state, false)", failure)
        self.assertIn("不完整的分享/小窗位保持关闭", failure)

    def test_validity_adapters_preserve_state_and_only_override_dart_false(self):
        raw, _ = fixture()
        host = ElfImage(bytes(raw))
        for share in (True, False):
            bit = 0x2 if share else 0x4
            for original in ("false", "true"):
                for feature_state in (0, bit):
                    with self.subTest(入口="分享" if share else "小窗",
                                      原结果=original, 功能位=feature_state):
                        run_validity_frame(self.menu, host, share, original, feature_state)

    def test_async_false_exits_return_feature_gated_dart_boolean(self):
        raw, _ = fixture()
        host = ElfImage(bytes(raw))
        for share in (True, False):
            bit = 0x2 if share else 0x4
            for feature_state in (0, bit):
                with self.subTest(入口="分享" if share else "小窗",
                                  功能位=feature_state):
                    run_validity_exit_frame(self.menu, share, feature_state)

    def test_dart_true_word_comes_from_csel_semantics(self):
        """真值编码必须由 csel 的 true 分支证明，不能从失败出口反推。"""
        real_host = ROOT / ".tmp-current-libapp.so"
        if not real_host.is_file():
            self.skipTest("缺少本地真实宿主样本")
        host = ElfImage(real_host.read_bytes())
        decoder = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        # _StringBase.isEmpty: 长度为零时 ne=0，csel 选择 x0=x22+0x20。
        true_word = next(decoder.disasm(host.read(0x86b90c, 4), 0x86b90c))
        false_word = next(decoder.disasm(host.read(0x86b910, 4), 0x86b910))
        csel = next(decoder.disasm(host.read(0x86b914, 4), 0x86b914))
        self.assertEqual("x0, x22, #0x20", true_word.op_str)
        self.assertEqual("x16, x22, #0x30", false_word.op_str)
        self.assertEqual("x0, x16, x0, ne", csel.op_str)

    def test_share_trampoline_keeps_host_x1_without_x2_copy(self):
        source = (ROOT / "app/src/main/cpp/launcher/launcher_menu_arm64.S").read_text(
            encoding="utf-8")
        self.assertNotIn("mov     x1, x2", source)
        # 两个分享入口都必须保留调用者的 x1；只允许修改桥参数寄存器 w0。
        for name in ("launcher_menu_primary_trace", "launcher_menu_secondary_trace"):
            body = source.split(name + ":", 1)[1].split(".size " + name, 1)[0]
            self.assertIn("bl      launcher_share_bridge", body)
            self.assertNotIn("mov     x1,", body)

    def test_share_trace_failure_branches_back_to_original_entries(self):
        source = (ROOT / "app/src/main/cpp/launcher/launcher_menu_arm64.S").read_text(
            encoding="utf-8")
        for name, slot, fallback in (
            ("launcher_menu_primary_trace", "launcher_menu_original_primary",
             ".Lshare_primary_fallback"),
            ("launcher_menu_secondary_trace", "launcher_menu_original_share_secondary",
             ".Lshare_secondary_fallback"),
        ):
            body = source.split(name + ":", 1)[1].split(".size " + name, 1)[0]
            self.assertIn("cbnz    w0, " + fallback, body)
            fallback_body = body.split(fallback + ":", 1)[1]
            self.assertIn(slot, fallback_body)
            self.assertIn("br      x16", fallback_body)

    def test_share_entry_ack_consumes_closure_and_failure_falls_back(self):
        raw, sites = fixture()
        host = ElfImage(bytes(raw))
        for primary in [True, False]:
            with self.subTest(entry="主" if primary else "次", path="ACK"):
                run_share_entry_frame(self.menu, host, primary, 0)
            with self.subTest(entry="主" if primary else "次", path="桥失败"):
                run_share_entry_frame(self.menu, host, primary, 6)
        with self.subTest(entry="主", path="免责声明"):
            run_share_entry_frame(self.menu, host, True, 0, disclaimer=True)

    def test_each_feature_publishes_its_own_call_site(self):
        """数量点不能同时充当主计划，否则会被数量组去重后静默跳过。"""
        source = (ROOT / "app/src/main/cpp/launcher/native_entry.cpp").read_text(
            encoding="utf-8")
        body = source.split("int install_launcher_batch(", 1)[1].split(
            "bool resolve_launcher_install_plan(", 1)[0]
        self.assertNotIn("main_specs", body)
        self.assertNotIn("kFirst", body)
        self.assertNotIn("batch_target_needed(count_target, first_target)", body)
        self.assertIn("requested_published ? 0 : -1", body)
        self.assertNotRegex(body, r"return\s+main_ok\s*&&")


if __name__ == "__main__":
    unittest.main()
