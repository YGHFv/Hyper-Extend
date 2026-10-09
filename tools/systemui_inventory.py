#!/usr/bin/env python3
"""Snapshot upstream SystemUI preferences without assuming an old hook works on OS4."""
import argparse
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"

AUDIT_REPAIRS = {
    "prefs_key_system_ui_statusbar_network_speed_style": ("status_bar_network_speed.style", ["F01"]),
    "prefs_key_system_ui_statusbar_network_speed_update_spacings": ("status_bar_network_speed.update_spacing", ["F03"]),
    "prefs_key_system_ui_status_bar_battery_style_change_location": ("status_bar_battery_style.change_location", ["F04"]),
    **{
        f"prefs_key_system_ui_statusbar_clock_{key}_1": (f"status_bar_clock.{key}", ["F05"])
        for key in ("left_margin", "right_margin", "vertical_offset")
    },
    **{
        f"prefs_key_system_ui_statusbar_clock_editor_{key}": (f"status_bar_clock.editor_{key}", ["F06"])
        for key in ("s", "b", "n", "p")
    },
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("reference", type=Path)
    parser.add_argument("--output", type=Path, default=Path("docs/systemui-upstream-inventory.json"))
    args = parser.parse_args()
    res = args.reference / "library/core/src/main/res"
    strings = {}
    for directory in ("values", "values-zh-rCN"):
        for file in sorted((res / directory).glob("*.xml")):
            for entry in ET.parse(file).getroot():
                if entry.tag == "string":
                    strings[entry.get("name")] = "".join(entry.itertext()).replace('\\"', '"')

    migrated = {
        "prefs_key_system_ui_control_center_hide_edit_botton": "control_center_hide_edit",
        "prefs_key_system_ui_volume_hide_foot_button": "volume_hide_collapsed_footer",
        "prefs_key_system_ui_control_center_expand_notification": "notification_auto_expand.packages",
        "prefs_key_system_ui_control_center_auto_clean_expand_notification": "notification_expanded_timeout",
        "prefs_key_system_ui_control_center_expand_notification_show_time": "notification_expanded_timeout.delay",
        "prefs_key_system_ui_control_center_media_control_media_button_layout_switch": "media_card_layout",
        "prefs_key_system_ui_control_center_media_control_media_button": "media_card_layout.button_size",
        "prefs_key_system_ui_control_center_media_control_media_button_custom": "media_card_layout.custom_button_size",
        "prefs_key_system_ui_control_center_media_control_media_button_actions_left_aligned": "media_card_layout.left_aligned",
        "prefs_key_system_ui_control_center_media_control_media_button_hide_seamless": "media_card_layout.hide_seamless",
        "prefs_key_system_ui_control_center_media_control_media_album_mode": "media_card_layout.album",
        "prefs_key_system_ui_control_center_media_control_media_button_mode": "media_card_layout.order",
        "prefs_key_system_ui_control_center_media_control_title_margin": "media_card_layout.title_margin",
        "prefs_key_system_ui_control_center_media_control_title_padding": "media_card_layout.artist_margin",
        "prefs_key_system_ui_control_center_media_control_media_button_size_switch": "media_card_text_size",
        "prefs_key_system_ui_control_center_media_control_title_size": "media_card_text_size.title",
        "prefs_key_system_ui_control_center_media_control_artist_size": "media_card_text_size.artist",
        "prefs_key_system_ui_control_center_media_control_time_view_text_size": "media_card_text_size.time",
        "prefs_key_system_ui_control_center_media_control_unlock_custom_actions": "media_unlock_custom_actions",
        "prefs_key_system_ui_lock_screen_allow_third_face": "lockscreen_third_party_biometrics",
        "prefs_key_system_ui_monet_overlay_custom": "systemui_monet_custom",
        "prefs_key_system_ui_monet_overlay_custom_color": "systemui_monet_custom.color",
        "prefs_key_system_ui_unlock_all_focus": "notification_unlock_focus",
        "prefs_key_system_ui_focus_notification_list": "notification_unlock_focus.packages",
        "prefs_key_system_ui_control_center_unimportant_notification": "notification_disable_auto_fold",
        "prefs_key_system_ui_lock_screen_scramble_pin": "lockscreen_scramble_pin",
        "prefs_key_system_ui_lock_screen_double_lock": "lockscreen_double_tap",
        "prefs_key_system_ui_lock_screen_not_disturb_mode": "lockscreen_hide_zen",
        "prefs_key_system_ui_lock_screen_unlock_tip": "lockscreen_hide_hint",
        "prefs_key_system_ui_control_center_zen_fix": "notification_zen_fix",
        "prefs_key_system_ui_unlock_clipboard": "clipboard_native_overlay",
        "prefs_key_system_ui_control_auto_close": "control_center_auto_collapse",
        "prefs_key_system_control_center_unlock_old": "control_center_unlock_old",
        "prefs_key_system_ui_control_center_redirect_notice": "notification_channel_settings",
        "prefs_key_system_ui_lock_screen_unlock_notification_restrict": "lockscreen_show_notifications",
        "prefs_key_system_ui_lock_screen_keep_notification": "lockscreen_keep_notifications",
        "prefs_key_system_ui_lock_screen_hide_status_bar": "lockscreen_hide_status_bar",
        "prefs_key_system_ui_lock_screen_disable_unlock_by_ble_toast": "lockscreen_hide_ble_toast",
        "prefs_key_system_ui_control_center_mute_visible_notice": "notification_mute_when_interactive",
        "prefs_key_system_ui_control_center_notification_disable_transparent": "notification_disable_transparent",
        "prefs_key_system_ui_notification_freeform": "notification_freeform",
        "prefs_key_system_ui_status_bar_icon_mobile_network_signal_mode": "status_bar_mobile.signal_mode",
        "prefs_key_system_ui_status_bar_icon_mobile_network_hide_card_1": "status_bar_mobile.hide_sim_1",
        "prefs_key_system_ui_status_bar_icon_mobile_network_hide_card_2": "status_bar_mobile.hide_sim_2",
    }
    items = []
    # A SystemUI hook can be configured on another app's page (old control center).
    external_keys = {"prefs_key_system_control_center_unlock_old"}
    files = sorted((res / "xml").glob("system_ui*.xml")) + [res / "xml/system_settings.xml"]
    for file in files:
        for entry in ET.parse(file).iter():
            key = entry.get(ANDROID + "key")
            if not key or (file.stem == "system_settings" and key not in external_keys):
                continue
            title = entry.get(ANDROID + "title", "")
            target = migrated.get(key)
            status = "implemented_static_verified" if target else (
                "needs_audit_existing_partial" if file.name.startswith("system_ui_status_bar") else "pending"
            )
            items.append({
                "page": file.stem,
                "key": key,
                "title": strings.get(title.removeprefix("@string/"), title),
                "kind": entry.tag,
                "status": status,
                "target": target,
            })
            if target and target.startswith("media_card_"):
                items[-1]["boundary"] = "Notification-center normal layout only; flip tiny screen and dynamic island remain unchanged. Native drawable scaling covers common and semantic bindings; animated drawables and touch targets are retained. Background and progress styles are pending."
            if key == "prefs_key_system_ui_control_center_hide_operator":
                items[-1].update(status="partially_implemented_static_verified", target="control_center_hide_carrier",
                    auditFindings=["P03"],
                    boundary="Hide-all mode only. Separator removal, device-name substitution and HD indicator behavior remain pending. Privacy indicators are unchanged.")
            if key == "prefs_key_system_ui_control_center_media_control_media_button_layout_switch":
                items[-1].update(status="partially_implemented_static_verified", auditFindings=["P01"])
            if key == "prefs_key_system_control_center_unlock_old":
                items[-1].update(status="repaired_static_pending_device", repairedFindings=["P02"],
                    boundary="SystemUI force flag and Settings selector gate are both hooked. User chooses the style; Lite/policy restrictions are retained. Device acceptance pending.")
            if key == "prefs_key_system_ui_control_center_media_control_media_button_size_switch":
                items[-1]["referenceNote"] = "Mapped from the upstream XML text-size switch. MediaViewSize.kt reads a different key, system_ui_control_center_media_control_text_size; this is an upstream XML/hook discrepancy, not evidence that the local switch is disconnected."
            if key == "prefs_key_system_ui_control_center_redirect_notice":
                items[-1].update(status="repaired_static_pending_device", repairedFindings=["P04"],
                    boundary="Menu-scoped shortcutId is forwarded only for matching package, UID and channel; Mi Push and app-settings fallback retained. Device acceptance pending.")
            if key in AUDIT_REPAIRS:
                target, findings = AUDIT_REPAIRS[key]
                items[-1].update(status="repaired_static_pending_device", target=target, repairedFindings=findings)
    revision = subprocess.check_output(["git", "-C", str(args.reference), "rev-parse", "HEAD"], text=True).strip()
    payload = {
        "reference": "https://github.com/ReChronoRain/HyperCeiler",
        "revision": revision,
        "scope": "All system_ui*.xml keys plus the old-control-center SystemUI hook configured in system_settings.xml; includes navigation/dependency rows, not all are hooks. Includes the Settings-app old-style selector gate.",
        "verification": "Static host-code checks and local tests only. No device acceptance yet.",
        "completenessAudit": "docs/migration-completeness-audit.md",
        "statusNote": "implemented_static_verified applies only to the named local scope, not full upstream parity. repaired_static_pending_device means the recorded defect has a source repair and regression coverage but no live acceptance; see repairedFindings and docs/migration-repair-0.1.3.md. Unreviewed rows remain needs_audit_existing_partial or pending.",
        "items": items,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(items)} upstream entries -> {args.output}")


if __name__ == "__main__":
    main()
