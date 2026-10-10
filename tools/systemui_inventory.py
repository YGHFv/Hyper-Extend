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
    "prefs_key_system_ui_status_bar_battery_icon": ("status_bar_icons.battery_icon", ["F07"]),
    "prefs_key_system_ui_status_bar_battery_percent": ("status_bar_icons.battery_percent", ["F08"]),
    "prefs_key_system_ui_status_bar_battery_percent_mark": ("status_bar_icons.battery_percent_mark", ["F08"]),
    "prefs_key_system_ui_status_bar_battery_style_enable_custom": ("status_bar_battery_style.custom", ["F08"]),
    "prefs_key_system_ui_status_bar_battery_style_font_size": ("status_bar_battery_style.font_size", ["F08"]),
    "prefs_key_system_ui_status_bar_battery_style_font_mark_size": ("status_bar_battery_style.font_mark_size", ["F08"]),
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
        "prefs_key_system_settings_more_notification_settings": "notification_importance",
        "prefs_key_system_ui_other_default_plugin_theme": "volume_default_theme",
        "prefs_key_system_ui_control_center_media_control_progress_on": "media_card_progress",
        "prefs_key_system_ui_control_center_media_control_progress_thickness": "media_card_progress.height",
        "prefs_key_system_ui_control_center_media_control_always_dark": "media_card_always_dark",
        "prefs_key_system_ui_control_center_media_control_background_mode": "media_card_background.mode",
        "prefs_key_system_ui_control_center_media_control_panel_background_blur": "media_card_background.blur",
        "prefs_key_system_ui_control_center_media_control_control_color_anim": "media_card_background.transition",
        "prefs_key_system_ui_lock_screen_show_charging_cv": "lockscreen_charging_info",
        "prefs_key_system_ui_show_charging_c_more": "lockscreen_charging_info.milliamps",
        "prefs_key_system_ui_show_battery_temperature": "lockscreen_charging_info.temperature",
        "prefs_key_system_ui_lock_screen_show_spacing_value": "lockscreen_charging_info.custom_interval",
        "prefs_key_system_ui_lock_screen_show_spacing": "lockscreen_charging_info.interval",
        "prefs_key_system_ui_lock_screen_bottom_left_button": "lockscreen_hide_left_shortcut",
        "prefs_key_system_ui_lock_screen_hide_camera": "lockscreen_hide_right_shortcut",
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
        "prefs_key_system_ui_statusbar_network_icon_enable": "status_bar_dual_row_signal",
        "prefs_key_system_ui_status_mobile_network_icon_style": "status_bar_dual_row_signal.style",
        "prefs_key_system_ui_statusbar_mobile_network_icon_size": "status_bar_dual_row_signal.scale",
        "prefs_key_system_ui_statusbar_mobile_network_icon_left_margin": "status_bar_dual_row_signal.left",
        "prefs_key_system_ui_statusbar_mobile_network_icon_right_margin": "status_bar_dual_row_signal.right",
        "prefs_key_system_ui_statusbar_mobile_network_icon_vertical_offset": "status_bar_dual_row_signal.vertical",
        "prefs_key_system_ui_control_center_remove_notif_num_limit": "notification_remove_count_limit",
        "prefs_key_system_ui_status_bar_mobile_type_custom": "status_bar_mobile_type_text.text",
        "prefs_key_system_ui_status_bar_icon_show_mobile_network_type": "status_bar_mobile_type_display.mode",
        "prefs_key_system_ui_statusbar_mobile_type_enable": "status_bar_mobile_type_display.separate",
        "prefs_key_system_ui_statusbar_mobile_type_left": "status_bar_mobile_type_display.left",
        "prefs_key_system_ui_statusbar_mobile_type_bold": "status_bar_mobile_type_display.bold",
        "prefs_key_system_ui_statusbar_mobile_type_font_size": "status_bar_mobile_type_display.size",
        "prefs_key_system_ui_statusbar_mobile_type_left_margin": "status_bar_mobile_type_display.left_margin",
        "prefs_key_system_ui_statusbar_mobile_type_right_margin": "status_bar_mobile_type_display.right_margin",
        "prefs_key_system_ui_statusbar_mobile_type_vertical_offset": "status_bar_mobile_type_display.vertical",
        "prefs_key_system_ui_statusbar_clock_all_status_enable": "status_bar_clock",
        "prefs_key_system_ui_disable_clock_synch": "status_bar_clock.big_format_mode",
        "prefs_key_system_ui_statusbar_clock_pad_hide": "status_bar_clock.pad_hide",
        "prefs_key_system_ui_statusbar_clock_style": "status_bar_clock.style",
        "prefs_key_system_ui_statusbar_clock_double_1": "status_bar_clock.alignment",
        "prefs_key_system_ui_statusbar_clock_double_spacing_margin_1": "status_bar_clock.line_spacing",
        "prefs_key_system_ui_statusbar_clock_fixedcontent_width_1": "status_bar_clock.fixed_width",
        **{f"prefs_key_system_ui_statusbar_clock_{upstream}": f"status_bar_clock.{local}"
           for upstream, local in (("bold", "bold"), ("big_bold", "big.bold"),
                                   ("small_bold", "mini.bold"), ("pad_bold", "pad.bold"))},
        **{f"prefs_key_system_ui_statusbar_clock_{field}_{index}": f"status_bar_clock.{role}{local}"
           for index, role in ((1, ""), (2, "big."), (3, "mini."), (4, "pad."))
           for field, local in (("size", "size"), ("left_margin", "left_margin"),
                                ("right_margin", "right_margin"), ("vertical_offset", "vertical_offset"))},
    }
    items = []
    # A SystemUI hook can be configured on another app's page (old control center).
    external_keys = {"prefs_key_system_control_center_unlock_old", "prefs_key_system_settings_more_notification_settings"}
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
            if target and target.startswith("status_bar_dual_row_signal"):
                items[-1]["boundary"] = "OS4 202602260 only; two active ordinary cellular SIM icons, per-binding visibility facade, upstream four asset styles, signed offsets. Default-data SIM is upper/anchor; no stale-level reuse. Unknown/no-voice/satellite/airplane/single-SIM states retain native layout. No live dual-SIM acceptance."
                items[-1]["evidenceManifest"] = "docs/dual-row-systemui-evidence.json"
            if target == "notification_remove_count_limit":
                items[-1]["boundary"] = "OS4 202602260 overflow-dismiss listener only. Coordinator attach, user/app cancellation and system-server posting limits are retained. No live notification flood test."
                items[-1]["evidenceManifest"] = "docs/dual-row-systemui-evidence.json"
            if target == "status_bar_mobile_type_text.text":
                items[-1]["boundary"] = "Custom non-empty native mobile-type names only (max 8 Unicode code points); audited getMobileTypeName caller is deoptimized. Native no-service, visibility and text measurement remain unchanged. Independent display/type policies are a separate feature. No live acceptance."
                items[-1]["evidenceManifest"] = "docs/mobile-type-text-systemui-evidence.json"
            if target and target.startswith("status_bar_mobile_type_display."):
                items[-1]["boundary"] = "OS4 202602260 per-binding relays: five display modes, separate native text view, physical left/right, font and signed offsets. Preserve native measurement/tint/animation tags and root visibility. No stale no-service labels; satellite/unknown states fall back. Default separate mode transfers native visibility rather than forcing text when all native type variants are hidden. Host WiFi availability and per-SIM data connection drive conditional modes. No device acceptance; deferred until migration completion."
                items[-1]["evidenceManifest"] = "docs/mobile-type-display-systemui-evidence.json"
            if target and target.startswith("status_bar_clock"):
                items[-1]["boundary"] = "OS4 202602260 only: five audited view IDs, per-role styling, dual-line order/alignment/spacing, fixed status width and pad-date hiding. Native updates/content descriptions remain; shared calendar time is restored after synchronous formatting. Default keeps existing independent big-clock formats; explicit sync uses only the status time line. Horizontal clock format stays native. Unified device acceptance pending; not full runtime parity."
                items[-1]["evidenceManifest"] = "docs/clock-systemui-evidence.json"
                if key == "prefs_key_system_ui_disable_clock_synch":
                    items[-1]["referenceNote"] = "Upstream Boolean disables default synchronization. Local choice intentionally defaults to independent (0) to preserve existing backups; sync is explicit (1). This is not a raw-value-compatible preference import."
            if key == "prefs_key_system_ui_status_bar_big_mobile_network_type":
                items[-1]["referenceNote"] = "Upstream XML explicitly hides this preference; current libhook source has no matching preference consumer. Do not add an empty local switch or treat it as the independently wired mobile-type display feature. Applicability remains unaudited."
            if key in {"prefs_key_system_ui_statusbar_mobile_type_enable", "prefs_key_system_ui_status_bar_icon_show_mobile_network_type"}:
                items[-1]["referenceNote"] = "Upstream MobileNetworkTypeSettings and SystemUIB/SystemUIV wire MobileTypeSingle2Hook. Local independent display and policies use a separate feature from custom text; native views are not removed and drawable measurement is not disabled."
            if key == "prefs_key_system_settings_more_notification_settings":
                items[-1].update(status="repaired_static_pending_device",
                    boundary="Settings 17/37 APK has 42 verified DEX members including ChannelPanelActivity routing to notification.app.ChannelNotificationSettings. Both channel pages bind onResume and dispatch their own updateDependents; one shared visibility hook, copied latest channel plus importance lock and server read-back, native restrictions retained. Device logs confirm app-page listener bound at level 1; user confirms adjustment works. SystemUI stats-only migration omitted icon filtering: now scoped toModel/shouldSuppressVisualEffect(32) adds native status-bar suppression for ranking 0/1 without trimming the shared notification list. Icon hide/restore and full save/re-entry acceptance pending; see docs/notification-importance-icon-2026-10-10.md.",
                    settingsEvidenceManifest="docs/notification-importance-settings-evidence.json")
            if target and target.startswith("media_card_progress"):
                items[-1].update(status="partially_implemented_static_verified",
                    boundary="Only original notification-center shader-track thickness (device levels 1/2); normal/tiny-screen restoration, native pressed maximum and touch bounds retained. No replacement seekbar, comet, primary-device path or island migration. Stored tenths of dp, unlike upstream's inconsistent thickness conversion.")
            if target in {"lockscreen_hide_left_shortcut", "lockscreen_hide_right_shortcut"}:
                items[-1].update(boundary="OS4 SystemUI 202602260 plus com.miui.aod shortcut plugin 22446301 only, loaded inside SystemUI via the existing shared plugin factory hook. Independently hide each side, guard icon hit test/click/TalkBack/selected-side launch; preserve native gesture reset/up and persisted shortcut data. Right hides the entire right slot even after app substitution, not all camera launch routes. Restore only owned visibility on detach/rebind/release/safety. No new AOD scope or ambient-display changes. Device acceptance pending.",
                    evidenceManifest="docs/lockscreen-shortcut-aod-evidence.json",
                    systemUiEvidenceManifest="docs/lockscreen-shortcut-systemui-evidence.json")
                if target == "lockscreen_hide_left_shortcut":
                    items[-1].update(status="partially_implemented_static_verified",
                        relatedTargets=["lockscreen_left_flashlight"],
                        boundary="OS4 SystemUI 202602260 plus AOD shortcut plugin 22446301: native/hide plus opt-in flashlight replacement. Hide has priority. Main-display ordinary keyguard with an already visible native entry only. Hold >=400ms and release, TalkBack click; slop/history/multi-pointer/cancel handling, native controller availability/force-off/battery limits, listener and owned drawable/tint/description restoration. No stored shortcut data edits. Outer host movement return/cleanup chain inspected, but no device acceptance.",
                        evidenceManifest="docs/lockscreen-flashlight-aod-evidence.json",
                        systemUiEvidenceManifest="docs/lockscreen-flashlight-systemui-evidence.json",
                        remaining="Bounded flashlight replacement implemented; upstream press-scale animation and exact theme assets, absent native entry and secondary-display replacement remain pending/excluded. Still partial, not a completed upstream-parity claim.")
            if target and target.startswith("lockscreen_charging_info"):
                items[-1].update(boundary="OS4 202602260 native vertical indication area only. Separate detail row while the native battery indication is actually visible; keep its text/click/rotation and font. BatteryManager current plus battery broadcast voltage/temperature, battery-side estimated power, optional mA/temperature and 1-5s half-second interval (default 3s). Worker-only sampling, one in flight per binding, generation cancellation on hide/detach/rebind, bounded queue. AOD/tiny/reverse/protection/fault/wireless-reposition states keep native. Unsupported/stale readings omitted; no sysfs/root access or device acceptance.",
                    evidenceManifest="docs/lockscreen-charging-systemui-evidence.json")
            if target == "lockscreen_charging_info.interval":
                items[-1]["referenceNote"] = "Uses the XML preference and preserves half-second values. Upstream polling reads a mismatched statusbar-prefixed key and integer-divides before multiplication; those defects are not copied."
            if target == "media_card_always_dark":
                items[-1]["boundary"] = "OS4 202602260 native notification media only. Seven material-effect branches use scoped night resource contexts; foreground update restores controller context in finally. Native AOD listener, transition guards, clear/apply, seekbar visibility and playback remain. Tiny screen and island unchanged. No custom artwork/gradient backgrounds or device acceptance. Safe-mode restoration retains newer native background writes."
                items[-1]["evidenceManifest"] = "docs/media-dark-systemui-evidence.json"
            if target and target.startswith("media_card_background."):
                items[-1].update(status="partially_implemented_static_verified",
                    boundary="OS4 202602260 ordinary unlocked notification-center media only. Four local artwork/mosaic/gradient modes, 192px host-artwork snapshots, real zero blur, opt-in 333ms cover-only transition, bounded queue and excluded-state deferral, native clear/reapply and original night foreground. Fixed dark contrast rather than Monet colors; deterministic mirrored mosaic rather than random composition. Keyguard/AOD/tiny-screen/island, inverse colors, Monet foreground/color animation, resize animation and ambient light remain native/pending. Shared hook owner with always-dark; lifecycle repairs and cover transition are static-only, not full upstream parity or device acceptance.",
                    evidenceManifest="docs/media-background-systemui-evidence.json")
            if target == "media_card_background.transition":
                items[-1]["referenceNote"] = "Partial mapping of upstream color-animation preference: only background cover crossfade is implemented. Native fixed night foreground does not animate between Monet palettes. Default off; skips hidden/detached/replaced/excluded cards and system-disabled animations. No claim of complete color-animation parity."
            if key == "prefs_key_system_ui_control_center_media_control_background_mode":
                items[-1]["referenceNote"] = "OS4 uses MediaViewBinder -> mediaViewEffectsMap -> seven native MediaView*Effect branches, not upstream updateMediaBackground. Current local rendering is deliberately partial with documented visual/palette and lockscreen boundaries. Do not promote this row or the whole media page to complete."
            if key == "prefs_key_system_ui_other_default_plugin_theme":
                items[-1]["boundary"] = "Default theme only in three verified volume call sites; shared control-center ThemeUtils consumers remain unchanged. No device acceptance."
            if key == "prefs_key_system_ui_plugin_enable_volume_blur":
                items[-1].update(status="already_supported_by_current_host",
                    boundary="MT plugin 183022200 Util.isSupportBlurS() returns constant true. No redundant switch/hook added; low-end/material gates retained.")
            if key == "prefs_key_system_ui_control_center_unimportant_notification":
                items[-1].update(evidenceManifest="docs/notification-fold-menu-evidence.json",
                    boundary="Four automatic-fold caller scopes plus current-menu-only canCustomFold suppression for unfolded notifications. Native move-out action, unrelated consumers, saved categorization and privacy filters retained. Full adapter readiness gate and caller deoptimization; device acceptance pending.")
            if key == "prefs_key_system_framework_other_rotation_button_int":
                items[-1].update(status="covered_by_existing_feature_per_user", target="rotation_suggestion",
                    boundary="User requested using the existing disable-rotation-suggestion feature in Other; upstream alternative display modes are intentionally not migrated.")
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
                if any(finding in {"F07", "F08"} for finding in findings):
                    items[-1]["boundary"] = "Battery visibility/font composition repair; cached OS4 host evidence and local policy tests only. Hook ordering, rendering and lifecycle device acceptance pending; see docs/migration-battery-followup.md."
                if target.startswith("status_bar_clock"):
                    items[-1]["evidenceManifest"] = "docs/clock-systemui-evidence.json"
                    items[-1]["boundary"] = "Existing key and encoding retained by the clock-role extension; exact OS4 clock/calendar evidence refreshed. Synchronous formatting restores shared calendar state. Host theme/density styling and native text restoration reviewed; unified device acceptance remains pending."
    revision = subprocess.check_output(["git", "-C", str(args.reference), "rev-parse", "HEAD"], text=True).strip()
    payload = {
        "reference": "https://github.com/ReChronoRain/HyperCeiler",
        "revision": revision,
        "scope": "All system_ui*.xml keys plus old-control-center and notification-importance hooks configured in system_settings.xml; includes navigation/dependency rows, not all are hooks. Settings importance adapter has static APK evidence, not device acceptance.",
        "verification": "Static host-code checks and local tests only. No device acceptance yet.",
        "completenessAudit": "docs/migration-completeness-audit.md",
        "continuationAudit": "docs/systemui-next-audit-2026-10-10.md",
        "mobileTypeDisplayAudit": "docs/mobile-type-display-2026-10-10.md",
        "clockAudit": "docs/clock-migration-2026-10-10.md",
        "mediaDarkAudit": "docs/media-dark-migration-2026-10-10.md",
        "mediaBackgroundAudit": "docs/media-background-migration-2026-10-10.md",
        "mediaContinuationAudit": "docs/media-continuation-2026-10-10.md",
        "lockscreenChargingAudit": "docs/lockscreen-charging-2026-10-10.md",
        "lockscreenShortcutAudit": "docs/lockscreen-shortcut-2026-10-10.md",
        "lockscreenFlashlightAudit": "docs/lockscreen-flashlight-2026-10-10.md",
        "notificationFoldMenuAudit": "docs/notification-fold-menu-2026-10-10.md",
        "notificationImportancePanelAudit": "docs/notification-importance-panel-2026-10-10.md",
        "notificationImportanceIconAudit": "docs/notification-importance-icon-2026-10-10.md",
        "statusNote": "implemented_static_verified applies only to the named local scope, not full upstream parity. repaired_static_pending_device means the recorded defect has a source repair and regression coverage but no live acceptance; see repairedFindings and docs/migration-repair-0.1.3.md. Unreviewed rows remain needs_audit_existing_partial or pending.",
        "items": items,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(items)} upstream entries -> {args.output}")


if __name__ == "__main__":
    main()
