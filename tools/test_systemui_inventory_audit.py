"""剩余行审计台账的回归定义；不访问网络或设备。

这一组断言存在的理由：`tools/systemui_inventory.py` 会把「未映射行」按审计结论重新标类。
如果常量、证据 JSON 和实际剩余行三者对不上，清单就会悄悄漏掉某一行——而漏掉的那一行
在清单里长得和「已审计」完全一样。所以这里把三者钉在一起。
"""
import json
import unittest
from pathlib import Path

import systemui_inventory
from systemui_inventory import REMAINING_ROW_AUDIT, REMAINING_ROW_AUDIT_MANIFEST

# 测试既可以 `python -m unittest discover -s tools`（cwd=仓库根）运行，也可能在 tools/
# 目录里直接跑，所以清单路径按**模块所在仓库根**解析，而不是按当前工作目录。
REPO_ROOT = Path(systemui_inventory.__file__).resolve().parent.parent


class RemainingRowAuditTest(unittest.TestCase):
    # 审计前的实际剩余行：27 条 needs_audit_existing_partial + 1 条 pending。
    expected = {
        "prefs_key_system_ui_move_log_to_miui",
        "prefs_key_system_ui_statusbar_battery_detail_at_right",
        "prefs_key_system_ui_status_bar_strong_toast_test_short_text",
        "prefs_key_system_ui_status_bar_strong_toast_test_long_text",
        "prefs_key_system_ui_status_bar_icon_wifi",
        "prefs_key_system_ui_status_bar_icon_hotspot",
        "prefs_key_system_ui_status_bar_icon_airplane",
        "prefs_key_system_ui_status_bar_icon_data_saver",
        "prefs_key_system_ui_status_bar_icon_volume",
        "prefs_key_system_ui_status_bar_hide_icon_speakerphone",
        "prefs_key_system_ui_status_bar_hide_icon_call_record",
        "prefs_key_system_ui_status_bar_icon_mobile_network_signal_no_card",
        "prefs_key_system_ui_status_bar_icon_new_hd",
        "prefs_key_system_ui_status_bar_icon_small_hd",
        "prefs_key_system_ui_status_bar_icon_big_hd",
        "prefs_key_system_ui_status_bar_big_mobile_network_type",
        "prefs_key_system_ui_statusbar_music_tip",
        "prefs_key_system_ui_statusbar_music_switch",
        "prefs_key_system_ui_statusbar_music_show_app",
        "prefs_key_system_ui_statusbar_music_show_notific",
        "prefs_key_system_ui_statusbar_music_hide_aod",
        "prefs_key_system_ui_statusbar_music_show_aod_mode",
        "prefs_key_system_ui_statusbar_music_size_n",
        "prefs_key_system_ui_statusbar_music_hide_clock",
        "prefs_key_system_ui_statusbar_music_click_clock",
        "prefs_key_system_ui_statusbar_music_speed",
        "prefs_key_system_ui_statusbar_music_scroll_delay",
        "prefs_key_system_ui_statusbar_music_width",
    }

    def test_audit_covers_exactly_the_leftover_rows(self):
        self.assertEqual(self.expected, set(REMAINING_ROW_AUDIT))

    def test_every_status_is_an_audited_non_implementation(self):
        # 只断言「不是已实现状态」。注意不能简单用子串 `implemented`：状态名里合法地含有
        # 这个词（audited_carrier_found_not_migrated 说明「有载体但未迁移」），用子串判断
        # 会把正确的状态误判成已实现。
        implementation_states = {
            "implemented_static_verified",
            "partially_implemented_static_verified",
            "repaired_static_pending_device",
            "covered_by_existing_feature_per_user",
            "already_supported_by_current_host",
        }
        for key, status in REMAINING_ROW_AUDIT.items():
            with self.subTest(key=key):
                self.assertTrue(status.startswith("audited_"), status)
                self.assertNotIn(status, implementation_states)

    def test_evidence_manifest_matches_the_constants(self):
        payload = json.loads(
            (REPO_ROOT / REMAINING_ROW_AUDIT_MANIFEST).read_text(encoding="utf-8"))
        rows = {row["key"]: row for row in payload["rows"]}
        self.assertEqual(set(REMAINING_ROW_AUDIT), set(rows))
        for key, status in REMAINING_ROW_AUDIT.items():
            with self.subTest(key=key):
                self.assertEqual(status, rows[key]["status"])
                self.assertTrue(rows[key]["hostCarrier"])
                self.assertTrue(rows[key]["boundary"])
        # 证据文件本身必须声明它没有做设备动作。
        for flag in ("runtimeAccepted", "installed", "hostRestarted", "deviceFeatureActionsExecuted"):
            with self.subTest(flag=flag):
                self.assertIs(False, payload[flag])

    def test_manifest_is_wired_into_the_generator_payload(self):
        text = (REPO_ROOT / "tools" / "systemui_inventory.py").read_text(encoding="utf-8")
        self.assertIn('"remainingRowAudit": REMAINING_ROW_AUDIT_MANIFEST', text)
        self.assertIn("REMAINING_ROW_AUDIT.get(item[\"key\"])", text)

    def test_second_pass_corrections_are_recorded(self):
        """第二轮只读复核查出的三处更正必须留在证据里。

        第一版把「宿主上不存在 \"volume\" 字面量」「飞行模式由 MiuiPhoneStatusBarPolicy 发布」
        「共享列表语义不可静态判定」写成了结论，三处都经 dex xref / 精确大小写检索被推翻。
        如果后人把这些更正删掉、或把旧的错误结论又抄回去，这条断言会失败。
        """
        payload = json.loads(
            (REPO_ROOT / REMAINING_ROW_AUDIT_MANIFEST).read_text(encoding="utf-8"))
        rows = {row["key"]: row for row in payload["rows"]}

        corrections = payload.get("correctionsAppliedOnSecondPass")
        self.assertTrue(corrections)
        joined = " ".join(corrections)
        for key in (
            "prefs_key_system_ui_status_bar_icon_volume",
            "prefs_key_system_ui_status_bar_icon_airplane",
            "prefs_key_system_ui_status_bar_icon_hotspot",
        ):
            with self.subTest(key=key):
                self.assertIn(key, joined)

        volume = " ".join(rows["prefs_key_system_ui_status_bar_icon_volume"]["hostCarrier"])
        # 槽位字面量确实叫 "volume"（PhoneStatusBarPolicy.mSlotMute），共享列表那侧才叫 "mute"。
        self.assertIn("mSlotMute", volume)
        self.assertIn('"volume"', volume)
        # 不能留旧的错误说法。
        self.assertNotIn("no \"volume\" literal exists", volume)

        airplane = " ".join(rows["prefs_key_system_ui_status_bar_icon_airplane"]["hostCarrier"])
        self.assertIn("MiuiStatusBarSignalPolicy", airplane)
        self.assertNotIn("MiuiPhoneStatusBarPolicy;->updateAirplaneModeIcon", airplane)

        # 每行都标了它是第二轮复核过的，避免新旧结论混在一份文件里分不出。
        for key, row in rows.items():
            with self.subTest(key=key):
                self.assertEqual("second-pass-read-only", row.get("verificationPass"))


if __name__ == "__main__":
    unittest.main()
