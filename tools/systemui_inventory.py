#!/usr/bin/env python3
"""Snapshot upstream SystemUI preferences without assuming an old hook works on OS4."""
import argparse
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"

PENDING14_SOURCE_ROWS = {
    "prefs_key_system_ui_disable_miui_multi_win_switch": "window_hide_quick_button",
    "prefs_key_system_ui_remove_miui_multi_win_switch": "window_disable_quick_menu",
    "prefs_key_system_ui_disable_infinitymode_gesture": "window_disable_infinity_gesture",
    "prefs_key_system_ui_disable_bottombar": "window_hide_bottom_bar",
    "prefs_key_security_center_taplus": "control_center_taplus_tile",
    "prefs_key_system_framework_allow_manage_all_notifications": "notification_manage_system",
    "prefs_key_system_control_center_sunshine_new_mode": "control_center_sunlight_tile",
    "prefs_key_security_flash_light_switch": "flashlight_strength_limit",
}

# Rows resolved after the remaining-6 carrier audit. These are not the upstream
# launcher/audio/brightness hook bodies; each maps onto a verified current host
# carrier and is recorded with its own bounded contract.
PENDING14_RESOLVED_ROWS = {
    "prefs_key_system_ui_hide_navigation_bar": ("navigation_handle_custom",
        "docs/pending14-remaining6-carrier-audit-2026-10-11.json",
        "Upstream hooked the launcher NavStubView/RecentsContainer, but the current Home.apk has neither class (gesture code is Rust with no Java bridge). Rewritten onto the native Settings.Global.hide_gesture_line preference that SystemUI SettingsManager/NavigationSettings and WMShell MultiTaskingSettingsObserver actually consume. Writes only that one global key from the SystemUI process (verified granted WRITE_SECURE_SETTINGS); no sysfs, brightness, audio or navigation-mode writes. Now shipped as the fourth option ('改系统原生隐藏偏好') of the existing navigation_handle_custom card (so this row's local target is that card, not the removed systemui_hide_navigation_bar id) rather than as its own card, because both cards make the gesture line disappear and fought over the same global key; the old top-level key and its restore_on_disable child are migrated by schema v4->v5.",
        "Device acceptance pending. Only reconciles once per SystemUI process start; a later manual change is not re-overwritten. Does not prove the launcher/Rust gesture surface consumes the same key for every gesture path. The four processing modes are mutually exclusive by construction, so 'module-side draw interception plus native preference' at the same time is intentionally not offered."),

    # The remaining-5 batch resolved these rows by replacing the upstream root-only
    # node writes with verified current host carriers, or by mapping two upstream
    # rows onto the existing sunlight card. move_log_to_miui stays pending.
    "prefs_key_system_ui_control_center_snow_leopard_mode": ("systemui_snow_leopard_mute",
        "docs/remaining5-resolution-2026-10-12.json",
        "Upstream SnowLeopardModeTile killed android.hardware.audio.service_64 and chmod 000 /dev/snd/pcm*, restoring a hard-coded 0660 with no owner/mode snapshot, and it kept the mode only in a process field. Reimplemented as native master mute: AudioManager#setMasterMute/isMasterMute via IAudioService, whose setMasterMute_enforcePermission() checks MODIFY_AUDIO_ROUTING (verified granted=true on the device SystemUI). No root, no chmod, no HAL kill, no sysfs. Master mute is a runtime flag (volume_master_mute is null in all three settings domains on the device), so it self-clears on restart.",
        "Not a kill-the-audio-HAL equivalent: native master mute still excludes streams the platform exempts (alarms/calls) and does not silence vibration. Tile on/off is the real master-mute state, so there is no separate restore option and no detail page. Device acceptance pending."),
    "prefs_key_system_control_center_sunshine_new_mode_high": ("control_center_sunlight_tile",
        "docs/remaining5-resolution-2026-10-12.json",
        "Upstream exposed this as a second config row of the sunlight tile. Merged into the existing control_center_sunlight_tile card as the brightness-boost-mode choice (system default=1 / force maximum=2 / custom=3) together with the custom-brightness slider. Upstream chmod 777 + raw Integer.MAX_VALUE write onto the backlight sysfs node was rejected: the device node is 0644 system:system u:object_r:vendor_sysfs_graphics:s0 with max_brightness=16383, and SystemUI runs as uid u0_a204, so DAC and SELinux both refuse the write. Values now go to Settings.System.screen_brightness, bounded by PowerManager#getMinimum/MaximumScreenBrightnessSetting(), with the original brightness and mode snapshotted into the host process preferences and reconciled once at SystemUI application start.",
        "Bounded to the platform's own accepted brightness ceiling, so this is explicitly not upstream's push-the-panel-past-what-the-system-allows. The snapshot lives in the SystemUI process preferences, so it survives process and full restarts; a user brightness change made later is not re-overwritten until the next reconciliation. Device acceptance pending."),
    "prefs_key_system_control_center_sunshine_mode_brightness": ("control_center_sunlight_tile",
        "docs/remaining5-resolution-2026-10-12.json",
        "Same two upstream rows as prefs_key_system_control_center_sunshine_new_mode_high, both merged into control_center_sunlight_tile. This one supplies the custom-brightness slider (1..32767, default 2048). The written value is clamped to the device range rather than trusted verbatim, and an illegal device range falls back to no write (native behaviour retained) instead of sending the panel a value it does not accept.",
        "Clamping means a custom value outside the device range lands on that device's own limit rather than failing; only a device with an invalid range is skipped. No sysfs write, no root, no Integer.MAX_VALUE. Device acceptance pending."),
    "prefs_key_system_ui_qs_label": ("systemui_qs_label",
        "docs/remaining5-resolution-2026-10-12.json",
        "Upstream shipped this key inside an app:isPreferenceVisible=false category and never reads it: full DEX name searches for HideQsLabel/qsLabel/QS_label in HyperCeiler 2.10.166 return no consumer. The current device MiuiSystemUI.apk (202602260) does carry the carrier the feature needs: MiuiQSTileView has mLabel:TextView and mLabelContainer:ViewGroup, and handleStateChanged(QSTile$State) refreshes the label text, accessibility importance, content description and clickability but never touches mLabelContainer visibility. The feature is therefore defined as hide-the-tile-text-label and implemented by hooking after handleStateChanged and setting mLabelContainer to GONE.",
        "Classic SystemUI tile views only: the control-centre plugin (miui.systemui.plugin) uses a different ViewHolder and stays native. Only the visible label is hidden; QSTile$State content description and state text are untouched, so TalkBack still announces the tile. Restoration is VISIBLE because native never writes this visibility. Enabling/disabling needs a SystemUI restart to reinstall. Device acceptance pending."),
}

# 剩余未映射行的逐条审计结论（docs/systemui-remaining-row-audit-2026-10-12.json）。
# 这些状态**不是** implemented：audited_carrier_found_not_migrated 表示当前宿主
# 确实存在 MT 只读验证过的载体、但本地还没有适配；其余表示无载体/非功能/外部依赖。
# 之所以单列，是为了让「有载体但没做」「根本没载体」和「已实现」在清单里一眼可分。
REMAINING_ROW_AUDIT = {
    "prefs_key_system_ui_move_log_to_miui": "audited_no_current_carrier",
    "prefs_key_system_ui_statusbar_battery_detail_at_right": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_strong_toast_test_short_text": "audited_test_action_only",
    "prefs_key_system_ui_status_bar_strong_toast_test_long_text": "audited_test_action_only",
    "prefs_key_system_ui_status_bar_icon_wifi": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_hotspot": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_airplane": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_data_saver": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_volume": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_hide_icon_speakerphone": "audited_no_current_carrier",
    "prefs_key_system_ui_status_bar_hide_icon_call_record": "audited_no_current_carrier",
    "prefs_key_system_ui_status_bar_icon_mobile_network_signal_no_card": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_new_hd": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_small_hd": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_icon_big_hd": "audited_carrier_found_not_migrated",
    "prefs_key_system_ui_status_bar_big_mobile_network_type": "audited_not_a_feature",
    "prefs_key_system_ui_statusbar_music_tip": "audited_not_a_feature",
    **{f"prefs_key_system_ui_statusbar_music_{name}": "audited_external_dependency"
       for name in ("switch", "show_app", "show_notific", "hide_aod", "show_aod_mode",
                    "size_n", "hide_clock", "click_clock", "speed", "scroll_delay", "width")},
}

REMAINING_ROW_AUDIT_MANIFEST = "docs/systemui-remaining-row-audit-2026-10-12.json"

# 第二轮只读复核查实、写进清单的事实（原先被列为「不可静态判定」）：
# MiuiIconManagerUtils.RIGHT_BLOCK_LIST / CONTROL_CENTER_BLOCK_LIST 的成员资格
# 语义是 **隐藏**，不是「放到右侧」：IconManager.addHolder 对成员槽位强制 visible=true 再建视图，
# StatusBarIconControllerImpl.refreshIconGroup 逐子视图算 setBlocked(...)，
# MiuiStatusIconContainer.onLayout/onMeasure 只给 isIconBlocked()==false 的图标布点。
# 同时更正：槽位字面量 "volume" 在宿主上**存在**（PhoneStatusBarPolicy.mSlotMute），
# 共享列表里用的是另一个拼写 "mute"；飞行模式真实载体是 MiuiStatusBarSignalPolicy，
# 不是 MiuiPhoneStatusBarPolicy。详见该 manifest 的 correctionsAppliedOnSecondPass。
BLOCK_LIST_SEMANTICS = "shared block lists hide their members; the 'volume' slot literal still exists and the lists carry the other spelling 'mute'"

PENDING14_EVIDENCE = {
    "control_center_taplus_tile": "docs/taplus-tile-evidence.json",
    "notification_manage_system": "docs/notification-management-evidence.json",
    "control_center_sunlight_tile": "docs/sunlight-tile-evidence.json",
    "flashlight_strength_limit": "docs/flashlight-strength-evidence.json",
}

PENDING14_BOUNDARIES = {
    "notification_manage_system": "Exact SDK37/fingerprint/owner-user Settings row and native forced-notification query adaptation; both enableNotifications overloads deoptimized. Preserve notification permission, enterprise and ECM gates. Server adapts isBlockable only inside system-UID user channel updates; no global flag, package identity or serialization changes. Native XSpace linkage remains native. Saved notification choices are not undone on disable; page reload/reboot can be needed for full recovery.",
    "control_center_sunlight_tile": "Independent opt-in tile switches only native sunlight_mode through current owner-user Settings.System, with device capability, write permission and brightness-restriction gates. State means saved preference, not active high brightness. Native auto-brightness, light sensor, power/thermal and display policy remain native. No sysfs, forced brightness, high/custom mode or long-press launch. Saved deliberate choices are not undone on disable.",
    "flashlight_strength_limit": "Partial replacement: configurable next-enable strength ceiling, not upstream live brightness-slider takeover. Only current classId0 native torch-on branch with maximum100, owner user and native availability/policy gates. Scoped getFloat returns min(native value, configured ceiling), preserving superflash50%, force-off, low-battery, camera lifecycle and errors. No settings/sysfs/camera write by module; disable restores on next native enable.",
}

# These are verified XML categories or fragment-only navigation, not feature
# implementations. Their destination/child rows keep their independent status.
STRUCTURAL_ROWS = {
    "prefs_key_system_ui_control_center_media_control_media_custom": "MediaCardSettings",
    "prefs_key_system_ui_control_center_tile_settings": "TileSettings",
    "prefs_key_card_tile": "CardTileSettings",
    "prefs_key_statubar_mobile_network": "MobileNetworkSettings",
    "prefs_key_system_ui_status_bar_island_media_card": "IslandMediaCardSettings",
    "prefs_key_system_ui_status_bar_mobile_type": "MobileNetworkTypeSettings",
    "prefs_key_system_ui_statusbar_iconmanage_mobile_network": "DoubleLineNetworkSettings",
    "prefs_key_system_ui_controlcenter_old": None,
    "prefs_key_system_ui_navigation": None,
    "prefs_key_system_ui_monet": None,
    "prefs_key_system_ui_statusbar_mobile_type_group": None,
    "prefs_key_system_ui_statusbar_clock_pad_show": None,
}


def source_digest(path):
    data = path.read_bytes()
    if path.suffix not in {".png", ".webp", ".jpg", ".jpeg", ".gif"}:
        data = data.replace(b"\r\n", b"\n")
    return hashlib.sha256(data).hexdigest()

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

# These slots occur in the complete current native default lists. Other catalog
# aliases still need their actual producer/resource path and are not promoted.
ICON_SLOT_SOURCE_REPAIRS = {
    **{f"prefs_key_system_ui_status_bar_icon_{name}": f"status_bar_icons.{name}"
       for name in ("bluetooth", "bluetooth_battery", "location", "mic", "alarm_clock",
                    "nfc", "vpn", "zen", "car", "pad", "pc", "phone", "soundbox",
                    "soundbox_screen", "soundbox_group", "soundbox_group_stereo",
                    "tv", "headset", "wireless_headset")},
    "prefs_key_system_ui_status_bar_hide_icon_stealth": "status_bar_icons.stealth",
    "prefs_key_system_ui_status_bar_hide_icon_mute": "status_bar_icons.hide_mute",
}
ICON_PRESENTATION_REPAIRS = {
    "prefs_key_system_ui_status_bar_icon_wifi_network_indicator_new": "status_bar_icons.wifi_activity",
    "prefs_key_system_ui_status_bar_icon_wifi_standard": "status_bar_icons.wifi_standard",
    "prefs_key_system_ui_status_bar_notification_icon_maximum_enable": "status_bar_icons.notification_icon_max",
    "prefs_key_system_ui_status_bar_notification_icon_maximum": "status_bar_icons.notification_icon_max_value",
    "prefs_key_system_ui_status_bar_battery_charging": "status_bar_icons.battery_charging",
    "prefs_key_system_ui_status_bar_mobile_hide_roaming_icon": "status_bar_mobile.hide_roaming",
    "prefs_key_system_ui_status_bar_mobile_indicator": "status_bar_mobile.hide_indicator",
    "prefs_key_system_ui_status_bar_icon_vowifi": "status_bar_mobile.hide_vowifi",
    "prefs_key_system_ui_status_bar_icon_volte": "status_bar_mobile.hide_volte",
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
        **ICON_SLOT_SOURCE_REPAIRS,
        **ICON_PRESENTATION_REPAIRS,
        **{f"prefs_key_system_ui_statusbar_network_speed_{key}": f"status_bar_network_speed.{key}"
           for key in ("font_size_enable", "font_size", "font_style", "sec_unit", "hide", "hide_all",
                       "hide_slow", "swap_places", "icon", "align", "fixedcontent_width",
                       "spacing_margin", "left_margin", "right_margin", "vertical_offset")},
        "prefs_key_system_ui_status_bar_double_tap_to_sleep": "status_bar_double_tap",
        **{f"prefs_key_system_ui_status_bar_battery_style_{key}": f"status_bar_battery_style.{key}"
           for key in ("bold", "left_margin", "right_margin", "vertical_offset", "vertical_offset_mark")},
        "prefs_key_system_ui_control_center_statusbar_blur": "systemui_force_window_blur",
        "prefs_key_system_ui_control_center_tile_super_blur": "control_center_tile_material",
        "prefs_key_system_framework_background_blur_toast": "systemui_toast_blur",
        # 合并后：几何卡片并入 navigation_handle_custom 的「几何」处理方式，四条键名不变。
        "prefs_key_system_ui_navigation_custom": "navigation_handle_custom.mode",
        "prefs_key_system_ui_others_pct_style": "slider_percentage.style",
        "prefs_key_system_cc_volume_showpct_title": "slider_percentage.volume",
        "prefs_key_system_showpct_title": "slider_percentage.brightness",
        "prefs_key_system_ui_others_showpct_top": "slider_percentage.top",
        "prefs_key_system_showpct_use_blur": "slider_percentage.blur",
        "prefs_key_system_ui_lock_screen_blur_button": "lockscreen_shortcut_blur",
        "prefs_key_system_ui_lock_screen_hyper_blur_button": "lockscreen_shortcut_blur.advanced",
        "prefs_key_system_ui_lock_screen_blur_button_bg_color": "lockscreen_shortcut_blur.color",
        "prefs_key_system_ui_lock_screen_blur_button_radius": "lockscreen_shortcut_blur.radius",
        "prefs_key_system_ui_navigation_custom_height": "navigation_geometry.height",
        "prefs_key_system_ui_navigation_custom_height_land": "navigation_geometry.height_land",
        "prefs_key_system_ui_navigation_frame_custom_height": "navigation_geometry.frame",
        "prefs_key_system_ui_navigation_frame_custom_height_land": "navigation_geometry.frame_land",
        "prefs_key_system_ui_control_center_show_weather": "shade_header_weather",
        "prefs_key_system_ui_control_center_show_weather_city": "shade_header_weather.city",
        "prefs_key_system_ui_control_center_opt_notification_element_background_color": "notification_blend_colors",
        "prefs_key_system_ui_control_center_opt_notification_element_background_color_advanced": "notification_blend_colors.keyguard",
        "prefs_key_systemui_plugin_card_tiles_enabled": "control_center_card_tiles",
        "prefs_key_systemui_plugin_card_tiles_edit": "control_center_card_tiles.order",
        "prefs_key_system_ui_shade_header_gradient_blur": "shade_header_gradient_blur",
        "prefs_key_system_ui_disable_volume": "volume_native_dialog",
        "prefs_key_system_ui_disable_power": "power_native_menu",
        "prefs_key_system_ui_control_center_qs_big_open_color": "control_center_card_tile_color",
        "prefs_key_system_ui_control_center_qs_big_bg_color": "control_center_card_tile_color.background",
        "prefs_key_system_ui_control_center_qs_big_color": "control_center_card_tile_color.foreground",
        "prefs_key_system_control_center_old_enable": "classic_qs_layout",
        "prefs_key_system_control_center_old_qs_rows": "classic_qs_layout.rows_portrait",
        "prefs_key_system_control_center_old_qs_rows_horizontal": "classic_qs_layout.rows_landscape",
        "prefs_key_system_control_center_old_qs_columns": "classic_qs_layout.columns_portrait",
        "prefs_key_system_control_center_old_qs_columns_horizontal": "classic_qs_layout.columns_landscape",
        "prefs_key_system_control_center_old_qs_grid_columns": "classic_qs_layout.quick_portrait",
        "prefs_key_system_control_center_old_qs_grid_columns_horizontal": "classic_qs_layout.quick_landscape",
        # 合并后：原位数值就是 slider_percentage 的 style=1，两个上游开关分别对应子项。
        "prefs_key_system_ui_control_center_qs_brightness_top_value_show": "slider_percentage.brightness",
        "prefs_key_system_ui_control_center_qs_volume_top_value_show": "slider_percentage.volume",
        "prefs_key_system_ui_volume_collpased_column_press": "volume_long_press_expand",
        "prefs_key_security_center_reduce_bright_colors_tile": "control_center_dim_tile_icon",
        "prefs_key_system_ui_control_center_fix_tiles_list": "control_center_fix_tiles_list",
        "prefs_key_system_control_center_5g_new_tile": "control_center_fix_tiles_list",
        "prefs_key_system_settings_more_notification_settings": "notification_importance",
        "prefs_key_system_ui_navigation_handle_custom": "navigation_handle_custom",
        "prefs_key_system_ui_navigation_handle_custom_thickness": "navigation_handle_custom.radius",
        "prefs_key_system_ui_navigation_handle_custom_color": "navigation_handle_custom.light_background",
        "prefs_key_system_ui_navigation_handle_custom_color_dark": "navigation_handle_custom.dark_background",
        "prefs_key_system_ui_navigation_handle_custom_height": "navigation_handle_custom.bottom",
        "prefs_key_system_ui_navigation_handle_custom_width": "navigation_handle_custom.width_portrait",
        "prefs_key_system_ui_navigation_handle_custom_width_land": "navigation_handle_custom.width_landscape",
        "prefs_key_system_ui_other_default_plugin_theme": "volume_default_theme",
        "prefs_key_system_ui_control_center_rounded_rect": "control_center_tile_corners",
        "prefs_key_system_ui_control_center_rounded_rect_radius": "control_center_tile_corners.radius",
        "prefs_key_system_ui_control_center_qs_open_color": "control_center_tile_color",
        "prefs_key_system_ui_control_center_qs_bg_color": "control_center_tile_color.background",
        "prefs_key_system_ui_control_center_qs_color": "control_center_tile_color.icon",
        "prefs_key_system_ui_control_center_media_control_progress_on": "media_card_progress",
        "prefs_key_system_ui_control_center_media_control_progress_thickness": "media_card_progress.height",
        "prefs_key_system_ui_control_center_media_control_progress_mode": "media_card_progress.mode",
        "prefs_key_system_ui_control_center_media_control_progress_thumb_mode": "media_card_progress.thumb",
        "prefs_key_system_ui_control_center_media_control_progress_corner_radius": "media_card_progress.corner",
        "prefs_key_system_ui_control_center_media_control_progress_comet": "media_card_progress.comet",
        "prefs_key_system_ui_control_center_media_control_progress_round": "media_card_progress.round",
        "prefs_key_system_ui_control_center_media_control_always_dark": "media_card_always_dark",
        "prefs_key_system_ui_control_center_media_control_background_mode": "media_card_background.mode",
        "prefs_key_system_ui_control_center_media_control_panel_background_blur": "media_card_background.blur",
        "prefs_key_system_ui_control_center_media_control_control_color_anim": "media_card_background.transition",
        "prefs_key_system_ui_control_center_media_control_inverse_color": "media_card_background.inverse",
        "prefs_key_system_ui_control_center_media_control_ambient_light": "media_card_background.ambient",
        "prefs_key_system_ui_control_center_media_control_ambient_light_opt": "media_card_background.ambient_optimized",
        "prefs_key_system_ui_lock_screen_show_charging_cv": "lockscreen_charging_info",
        "prefs_key_system_ui_lock_screen_linkage_anim": "lockscreen_wallpaper_transition",
        "prefs_key_system_ui_lock_screen_linkage_anim_on": "lockscreen_wallpaper_transition.wake",
        "prefs_key_system_ui_lock_screen_linkage_anim_off": "lockscreen_wallpaper_transition.sleep",
        "prefs_key_system_ui_show_charging_c_more": "lockscreen_charging_info.milliamps",
        "prefs_key_system_ui_show_battery_temperature": "lockscreen_charging_info.temperature",
        "prefs_key_system_ui_lock_screen_show_spacing_value": "lockscreen_charging_info.custom_interval",
        "prefs_key_system_ui_lock_screen_show_spacing": "lockscreen_charging_info.interval",
        "prefs_key_system_ui_lock_screen_bottom_left_button": "lockscreen_hide_left_shortcut",
        "prefs_key_system_ui_lock_screen_hide_camera": "lockscreen_hide_right_shortcut",
        "prefs_key_system_ui_control_center_hide_edit_botton": "control_center_hide_edit",
        "prefs_key_system_ui_control_center_disable_device_managed": "control_center_hide_security_footer",
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
        # 合并后：渠道跳转成为 notification_importance 的菜单目标选择项。
        "prefs_key_system_ui_control_center_redirect_notice": "notification_importance.menu_target",
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
            if key in STRUCTURAL_ROWS:
                fragment = entry.get(ANDROID + "fragment")
                expected = STRUCTURAL_ROWS[key]
                assert (entry.tag == "PreferenceCategory" and expected is None) or (
                    entry.tag == "Preference" and expected and fragment and fragment.endswith("." + expected)
                ), f"Structural classification changed: {key}"
                items[-1].update(status="structural_navigation_only",
                    upstreamFragment=fragment,
                    boundary="Audited XML category or fragment navigation only; not an implemented feature. Destination and child preferences retain their own migration status, including missing island/card-list features. No empty local switch is created.")
            if target in {"volume_native_dialog", "power_native_menu"}:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/native-systemui-evidence.json",
                    codeReviewManifest="docs/native-card-continuation-2026-10-11.md",
                    boundary="SystemUI 202602260 main display only. Scoped ExtensionBuilder.withPlugin selection retains the original default Supplier, native build/notify/init/destroy, volume safety and global-action callbacks. No PackageManager component changes, eager provider instantiation, volume writes or power action. Three-hook readiness and caller deoptimization. Not compiled or device tested.",
                    remaining="Switching or safe-mode recovery requires SystemUI restart; no live switching. Native fallback rendering and plugin-specific feature coexistence await unified runtime acceptance. The upstream persistent component-disable mechanism is intentionally not copied.")
            if target and target.startswith("control_center_card_tile_color"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/card-tile-color-evidence.json",
                    sharedHostEvidenceManifest="docs/card-tile-color-systemui-evidence.json",
                    codeReviewManifest="docs/native-card-continuation-2026-10-11.md",
                    boundary="SystemUI 202602260/plugin 183022200 main-display default-theme drawable-backed QSCardItemView only. Independent enabled-background clone retains shape/layers/alpha animation; temporary native icon palette and owned title/status colors. Preserve native traffic-warning/restriction/material/transient-state priorities. Same-state refresh reapplies owned background/text without shared resource changes. Not compiled or device tested.",
                    remaining="Blend/glass/custom theme and filtered/stateful backgrounds remain native. Icon changes/disable wait for actual native setIcon; same-state early returns can retain old tint. Safe-mode full recovery needs restart. Not full upstream color/material parity.")
            if target == "shade_header_gradient_blur":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/shade-header-gradient-evidence.json",
                    codeReviewManifest="docs/native-card-continuation-2026-10-11.md",
                    boundary="SystemUI 202602260 main display only. Original selector runs first; opt-in independent header gradient retains gradientBlurSupported rather than forcing unsupported hardware. All seven exact xref callers are deoptimized together: Flow parameters, alpha, clipping, invalidation and drawing. No property/settings write, added layer or custom animator. Not compiled or device tested.",
                    remaining="Changes and safe-mode full recovery require SystemUI restart; cached Flow state may not update immediately. May increase power usage when whole-panel material is off. Does not implement the separate force-background-blur preference.")
            if target and target.startswith("media_card_"):
                items[-1]["boundary"] = "Notification-center normal layout only; flip tiny screen and dynamic island remain unchanged. Native drawable scaling covers common and semantic bindings; animated drawables and touch targets are retained. Background and progress styles are pending."
            if target and target.startswith("navigation_handle_custom"):
                items[-1].update(boundary="Merged card: the former gesture_line and navigation_geometry cards now live here as mutually-exclusive processing modes (MODE_APPEARANCE/MODE_HIDE/MODE_GEOMETRY) with a v2->v3 key migration; their sub-keys are unchanged. OS4 202602260 main-display only. Appearance: radius 0-5 dp, optional ARGB endpoints, optional portrait/landscape base widths and bottom distance; scoped onDraw and transition-info getters restore radius, width, bottom, geometry cache and paint in finally; zero radius now keeps the native radius instead of hiding drawing (that effect moved to the hide mode). Hide: onDraw short-circuit plus native hidden report. Geometry: three synchronous scopes share fixed startup dimensions with exact framework resource checks. Only one mode installs at a time; native pulse/press factors bound requested dimensions to real view geometry; touch/layout, visibility and user gesture settings remain. Exact callers deoptimized. Not compiled/tested or device accepted.",
                    evidenceManifest="docs/navigation-handle-systemui-evidence.json",
                    sizeEvidenceManifest="docs/navigation-handle-dimensions-evidence.json",
                    referenceNote="Upstream resource radius is half the rendered thickness (default 1.85 dp); local label states radius. Local colors default to follow-system, not forced upstream defaults. No dependency on hiding the navigation bar. The three modes are mutually exclusive because hide short-circuits the same drawing path appearance/geometry rely on.")
            if target and target.startswith("slider_percentage."):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/percentage-plugin-evidence.json",
                    lifecycleEvidenceManifest="docs/slider-value-plugin-evidence.json",
                    repairValidationManifest="docs/mt-reconnect-validation-2026-10-11.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only SystemUI202602260/plugin183022200 main-display unlocked UI. Mode2 owns non-interactive per-window overlay from main brightness/volume, secondary brightness, actual brightness-window SeekBar, classic MiuiBrightnessSlider and hardware-key VolumeColumn. Native callbacks execute once unchanged; classic mirror retained. Hardware panel follows active stream, uses native discrete progressToLevel while tracking and confirmed StreamState.level for hardware keys, removes at dismissal. Weak deferred show validates owner/stream/showing again; stop/detach/hide/disable/safety/30s cleanup. Mode1 reuses audited native control-center top-label adapter. No service writes, listener replacement or static singleton reads.",
                    remaining="Android native replacement volume dialog is not included. Native super-volume/out-of-range remains native; optional advanced blur retains host capability/configuration/cross-window gates with plain fallback. Not a raw preference-value importer: parent off replaces style0, local mode1 only uses current control-center top labels. Mode1 now releases owned labels before drawing after disable/safety/hide and preserves equal native rich text. Scoped compile/unit/lint and fresh host-text validation are linked separately; no runtime acceptance.",
                    classicEvidenceManifest="docs/percentage-legacy-systemui-evidence.json",
                    volumePanelEvidenceManifest="docs/percentage-legacy-plugin-evidence.json",
                    backdropEvidenceManifest="docs/owned-backdrop-framework-evidence.json",
                    backdropHostEvidenceManifest="docs/lockscreen-material-evidence.json")
            if target == "systemui_force_window_blur":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/force-blur-evidence.json",
                    frameworkEvidenceManifest="docs/force-blur-framework-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only SystemUI202602260 BlurUtils capability relaxation: retain actual CROSS_WINDOW hardware, disableBlur property and live cross-window setting, relax only high-end classification. No global ActivityManager hook. Newly enabled positive blur is main-display-only; native positive/zero requests retain geometry/transactions. Owned root/radius tracking, zero-only native cleanup on disable/capability withdrawal, old-root cleanup before retarget, detach/hide/30s cleanup. Zero-only capability scope preserves native valid-Surface check, scheduled frame transaction and early-wakeup end. No install-time service reads.",
                    remaining="Generic capability consumers cache state and may describe secondary displays, while module-enabled positive application is main-display-only. Capability/safety interactions and live scheduling need build/device acceptance. Safety bypasses both hooks, forfeits immediate cleanup and requires user restart. Unsupported hardware has no invented rendering backend; native true support remains unchanged. Framework declarations are cached-artifact evidence, not a fresh device capture. No compilation/tests/runtime acceptance.")
            if target == "control_center_tile_material":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/tile-material-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only real ColorBlendToken rendering, not the current no-op updateBlendBlur holders. Main-display ordinary small/card tile classic material only; native capability/default-theme/super-power/state/warning/detail/edit gates retained. Copy recognized endpoint tokens from exact plugin resources; preserve native interpolation, geometry, modes and glass path. Exact six hooks and 43 full dispatch callers; no installation-time singleton value reads. Native blend writes/clear/material takeover forfeit ownership. Weak view lifecycle restores latest native tokens only with matching state/config and proven ownership on disable/hide/detach/30s.",
                    remaining="Volume/ringer-button material, glass visual parity and first-frame runtime coverage remain open. Safety bypasses writer hooks and therefore forfeits safe immediate replay; full restoration then needs user restart/native refresh. Upstream entire hook body is commented out; this is a current-host bounded implementation, not evidence of a working upstream control. No compile/tests/render/device acceptance.")
            if target == "systemui_toast_blur":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/toast-material-evidence.json",
                    frameworkEvidenceManifest="docs/owned-backdrop-framework-evidence.json",
                    boundary="Source-only current SystemUI202602260 default text_toast/ToastLinearLayout. Owned standard BackgroundBlurDrawable with native capability/configuration/cross-window gates; skip existing native blur and plugin/custom Toasts. Preserve text/icon/padding/measurement/animation/presenter/accessibility/duration. Background only restores by identity. Detach/hide/keyguard/screen-off/disable/safety/30s cleanup; no eager ToastStub service initialization.",
                    remaining="No app-process custom Toast scope or forced blur capability. Cached framework declarations are not fresh device capture; no compilation/tests/render/runtime acceptance. Standard owned background is not claimed pixel-identical to upstream ToastStub material/shadow.")
            if target and target.startswith("lockscreen_shortcut_blur"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/lockscreen-material-evidence.json",
                    frameworkEvidenceManifest="docs/owned-backdrop-framework-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only SystemUI202602260/AOD22446301 main-display ordinary interactive keyguard. Add one owned circle behind each existing icon, preserve parent/native drawables/input/launch chain. BackgroundBlurDrawable or optional per-owned-view MiBlur, retain native configuration/hardware/cross-window enable gates. Actual geometry/alpha follow original image; tint contrast only for sufficiently opaque custom backgrounds, restore by identity. Hide/AOD/occlusion/going-away/detach/release/disable/safety remove owned view and clear blur. No service/singleton reads during installation.",
                    remaining="Cached framework artifact is not newly captured device evidence. Native backdrop render, animation/geometry, both backends and capability toggling need unified build and authorized device acceptance; no pixel parity claim or upstream raw-inset import.")
            if key in {"prefs_key_system_ui_navigation_handle_custom_height", "prefs_key_system_ui_navigation_handle_custom_width", "prefs_key_system_ui_navigation_handle_custom_width_land"}:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/navigation-handle-dimensions-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    referenceNote="Upstream XML explicitly disables all three and HandleLineCustom consumes none. Height is documented by XML as navigation_handle_bottom, not thickness. Implemented as a new exact-current-host adapter, not a claim upstream had working controls. -1 bottom/0 width retain native; no old width resource replacement.",
                    remaining="Requires user restart for config changes; live rotation/press/pulse/transition-export acceptance pending. No input-region or navigation-frame changes. Pure-policy and full-signature test definitions added, not executed.")
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
                    boundary="Settings 17/37 APK has 42 verified DEX members including ChannelPanelActivity routing to notification.app.ChannelNotificationSettings. Both channel pages bind onResume and dispatch their own updateDependents; one shared visibility hook, copied latest channel plus importance lock and server read-back, native restrictions retained. Device logs confirm app-page listener bound at level 1; user confirms adjustment works. SystemUI stats-only migration omitted icon filtering: now scoped toModel/shouldSuppressVisualEffect(32) adds native status-bar suppression for ranking 0/1 without trimming the shared notification list. The former notification_channel_settings card is merged into this card as its menu-target choice (default returns to the native app-settings route; a v3->v4 migration maps an explicitly-enabled old card to the channel target). Menu interception is now gated on this parent switch AND an explicit channel choice. Icon hide/restore and full save/re-entry acceptance pending; see docs/notification-importance-icon-2026-10-10.md.",
                    settingsEvidenceManifest="docs/notification-importance-settings-evidence.json")
            if target and target.startswith("navigation_geometry"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/navigation-geometry-evidence.json",
                    frameworkEvidenceManifest="docs/navigation-geometry-framework-evidence.json",
                    mergedInto="navigation_handle_custom",
                    boundary="Source-only SystemUI 202602260 main-display bottom gestural mode with forced-button fallback retained. Three synchronous scopes share fixed startup dimensions: Window/LayoutParams and providedInsets creation, view measurement/background frame, and nav color sampled region. Exact framework resource ID/name/type/package checks; no global resource mutation or settings writes. Frame is at least bar height, zero retains native, nonzero minimum 4dp. All four hooks and three callers required.",
                    remaining="Three-button, side bars and independent system_server layout policy are not overridden. Must validate WM/IME/rotation/accessibility interactions after deferred build. Enable/disable/edits/safe recovery need restart; no runtime geometry teardown. Cached framework constants/API evidence is labeled separately from current MT SystemUI evidence.",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md")
            if target and target.startswith("shade_header_weather"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/header-weather-evidence.json",
                    repairValidationManifest="docs/partial-feature-repair-validation-2026-10-11.json",
                    boundary="SystemUI 202602260 main-display current-user header. Weakly capture existing NavigationBar UserTracker, use its synchronized createCurrentUserContext and getUserId before/after query context creation and at callback/pre-draw; never default to owner user. Exact native WeatherFetcherController asynchronous actualWeatherData/hourlyData read with native cursor close/timeout. No provider/location/network writes. Owned accessible non-clickable label beside existing date/land-clock, no reparenting or native text mutation; bound by actual available width, obstacles and RTL. Attached-only listener, no broadcast receiver, weak callback, request generation checks, 6-hour data age bound. Per-binding 15-minute attempt cooldown and primitive weather snapshot survive temporary hide/detach; pending delivery and owned label do not. Disable/safety/user change/tracker loss clear both cache and cooldown. Stale or duplicate callbacks are rejected before bean parsing and cannot clear another user's request. Failed or cancelled attempts retain cooldown; an undelivered request can be superseded at the next interval.",
                    remaining="No display until the existing native UserTracker is captured; per-user weather provider permissions/data and device layout/animation acceptance still unverified. A new header instance has its own cooldown; this is not a global provider cache or a reproduction of upstream Folme reparenting. Scoped repair validation is recorded separately; legacy inventory status is not a device-acceptance claim.",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md")
            if target and target.startswith("notification_blend_colors"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/notification-blend-evidence.json",
                    boundary="Source-only; SystemUI 202602260 main-display native blur effects. Exact 27 effect/bridge scopes and seven dispatch callers retain outer shade/heads-up/keyguard/AOD identity through nested glass fallbacks. Copy only color slots in native blend array, preserve blend modes, capability, clipping, animation and clear. No global resource overrides, no static singleton initialization. Keyguard opt-in separately defaults off. All hooks/deoptimizations required.",
                    remaining="Native glass token rendering, full AOD and tiny-screen resource consumers stay native; not pixel parity for those branches. Disable takes effect at next native effect application, safe-mode full recovery requires recreation/restart. Not built/tested.",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md")
            if target and target.startswith("control_center_card_tiles"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/card-tile-list-evidence.json",
                    boundary="Source-only, no compilation/tests/device acceptance. SystemUI 202602260/plugin 183022200 main display. Native lazy initializer stores one immutable ordered list before QSController constructor builds exclusions; all three consumers share it. Exact detail scope suppresses only a second successful tile creation for the same controller/spec, retaining first view and tile. Native callbacks, material, shrink/distribution and VoWiFi creation/cleanup remain untouched. All three hooks and discovery/deoptimization must be ready.",
                    remaining="Text order editor instead of upstream drag UI. Ten ordinary upstream specs plus explicit none; native hardware factory may omit unavailable tiles. VoWiFi order/visibility stays native, not full upstream suppression/reordering parity. Enable/disable/edit/safe recovery require controller recreation or user restart; no live migration of existing controllers.",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md")
            if target and target.startswith("media_card_progress"):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="2026-10-11 source-only continuation; no compilation/tests/lint/runtime validation. Exact SystemUI 202602260 main-display unlocked normal media only. Keep native SeekBar/view/listener/accessibility/render-state applier; drawing-only wave/straight/comet/thumb/corner styles. Current MiuiMediaSeekBarProgressOwner observer supplies animation state, not the removed upstream controller observer. Animate only enabled/listening/playing/non-scrubbing with system animations enabled. Shader-only custom foreground derives from actual themed time text because primary-color setters are no-ops; primary devices use native fields. Native mode0 levels0/1/2 use both temporary mProgressHeight and receiver-bound ProgressBar.getMaxHeight during native primary/high-end drawing, with finally restoration and available-height bounds. No measuring or touch-area mutation. Attached/shown/awake/window-visible only, pressed/dragging native. Weak controller/holder identities and five-hook all-ready gate.",
                    evidenceManifest="docs/media-progress-evidence.json",
                    supplementalEvidenceManifests=["docs/media-native-height-evidence.json"],
                    remaining="Custom styles do not reproduce native material shaders. Lock screen/AOD/tiny-screen/island stay native; no full upstream visual-parity claim. Height/corner use local tenths-of-dp, not raw upstream imports. Mode 0 preserves original pressed/max behavior.")
            if target == "control_center_hide_security_footer":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="2026-10-11 source-only migration; no compilation/tests/lint/runtime validation. Exact SystemUI 202602260/plugin 183022200 main-display disclosure row only. Classic native display-update runnable runs first; only classId 1 row visibility is then hidden. Plugin availability runs first then returns false, caller/discovery deoptimized. No DevicePolicyManager/SecurityController/VPN/certificate/work-profile state spoofing or restriction removal. Disable restores on next native display update/distribution, not instant safe-mode cleanup.",
                    evidenceManifest="docs/security-footer-systemui-evidence.json",
                    pluginEvidenceManifest="docs/security-footer-plugin-evidence.json")
            if target in {"lockscreen_hide_left_shortcut", "lockscreen_hide_right_shortcut"}:
                items[-1].update(boundary="OS4 SystemUI 202602260 plus com.miui.aod shortcut plugin 22446301 only, loaded inside SystemUI via the existing shared plugin factory hook. Independently hide each side, guard icon hit test/click/TalkBack/selected-side launch; preserve native gesture reset/up and persisted shortcut data. Right hides the entire right slot even after app substitution, not all camera launch routes. Restore only owned visibility on detach/rebind/release/safety. No new AOD scope or ambient-display changes. Device acceptance pending.",
                    evidenceManifest="docs/lockscreen-shortcut-aod-evidence.json",
                    systemUiEvidenceManifest="docs/lockscreen-shortcut-systemui-evidence.json")
                if target == "lockscreen_hide_left_shortcut":
                    # Upstream models this as ONE three-way dropdown (0=default/1=hide/2=flashlight).
                    # The two local cards it had been split into are merged back into one card with
                    # that choice (schema v5->v6); hide and flashlight no longer fight over one view.
                    items[-1].update(status="partially_implemented_static_verified",
                        relatedTargets=["lockscreen_left_flashlight"],
                        boundary="OS4 SystemUI 202602260 plus AOD shortcut plugin 22446301: one card with a three-way choice (keep native / hide / flashlight), matching upstream' s own single dropdown. Hide intercepts that side's click and swipe launch; flashlight toggles on a >=400ms hold and release with TalkBack click. Main-display ordinary keyguard with an already visible native entry only. Slop/history/multi-pointer/cancel handling, native controller availability/force-off/battery limits, listener and owned drawable/tint/description restoration. No stored shortcut data edits. Outer host movement return/cleanup chain inspected, but no device acceptance.",
                        evidenceManifest="docs/lockscreen-flashlight-aod-evidence.json",
                        systemUiEvidenceManifest="docs/lockscreen-flashlight-systemui-evidence.json",
                        remaining="Bounded flashlight replacement implemented; upstream press-scale animation and exact theme assets, absent native entry and secondary-display replacement remain pending/excluded. The two former cards are now mutually exclusive by construction, so the old hardcoded hide-priority rule is gone. Still partial, not a completed upstream-parity claim.")
            if target and target.startswith("lockscreen_charging_info"):
                items[-1].update(boundary="OS4 202602260 native vertical indication area only. Separate detail row while the native battery indication is actually visible; keep its text/click/rotation and font. BatteryManager current plus battery broadcast voltage/temperature, battery-side estimated power, optional mA/temperature and 1-5s half-second interval (default 3s). Worker-only sampling, one in flight per binding, generation cancellation on hide/detach/rebind, bounded queue. AOD/tiny/reverse/protection/fault/wireless-reposition states keep native. Unsupported/stale readings omitted; no sysfs/root access or device acceptance.",
                    evidenceManifest="docs/lockscreen-charging-systemui-evidence.json")
            if target and target.startswith("lockscreen_wallpaper_transition"):
                items[-1].update(boundary="OS4 202602260 ordinary main-display keyguard wallpaper black/reveal animation only. Current owner is KeyguardPanelViewController, not old ClockBaseAnimation. Per-call identity-scoped outline factory replaces only the selected ease with a fresh 100-1600ms decelerate style. Native config/listeners/tracker/SurfaceControl/wake-lock release and cancellation remain; no shared ease mutation or new animation loop. Full-AOD/depth/video/secondary-display/third-party-theme/occluded/bouncer/unlock/power-save and native no-animation routes remain unchanged. All-ready and exact-version gates; no device acceptance.",
                    evidenceManifest="docs/lockscreen-wallpaper-systemui-evidence.json",
                    referenceNote="Display milliseconds, not upstream XML's misleading divided rate. Defaults follow XML: wake 300ms, sleep 200ms (upstream hook fallback says sleep 300). Wake uses upstream style 20 instead of the current host spring. This is bounded wallpaper dimming timing, not a replacement of full AOD, clock, depth, notification or unlock transitions.")
            if target == "control_center_fix_tiles_list":
                items[-1].update(boundary="SystemUI 202602260 and control-center plugin 183022200 only. Append six audited factory-backed specs to editor candidates, preserving the native list and order. Classic show uses one exact Context/string read; plugin addStockTiles scopes one getTilesStock read on its worker. Native isAvailable, exclusion lists, permissions, collection/destruction and explicit user save remain. No shared resource/Lazy/repository or restore-filter edits; no automatic tile creation/click/save by the module. Per-adapter all-ready and version gates; device acceptance pending.",
                    evidenceManifest="docs/stock-tiles-systemui-evidence.json",
                    pluginEvidenceManifest="docs/stock-tiles-plugin-evidence.json",
                    referenceNote="Do not replace current phone/pad stock resources with upstream's old whole list, which omits current satellite/recorder/cast specs. Adds reduce_brightness,inversion,saver,dark,onehanded,color_correction only. user/dnd have no current native factory branch and are intentionally excluded. Closing the feature does not remove user-added tiles or rewrite layout; full upstream list parity is not claimed.")
            if target == "control_center_dim_tile_icon":
                items[-1].update(boundary="SystemUI 202602260 ReduceBrightColorsTile only. Native handleUpdateState runs first; replace icon alone with the attributed upstream 24dp vector wrapped in host DrawableIcon, with per-tile/configuration weak caching for stable identity. Native state/value/label/accessibility/tint/availability/click/long-click/upgrade-dialog remain. No resource-ID spoofing, brightness writes or tile-list edits. Disabled/safe mode restores on next native state refresh, not instantly. Device acceptance pending.",
                    evidenceManifest="docs/dim-tile-icon-systemui-evidence.json",
                    pluginEvidenceManifest="docs/dim-tile-icon-plugin-evidence.json",
                    referenceNote="Current host uses DrawableIconWithRes via maybeLoadResourceIcon, not upstream ResourceIcon.get. Plain DrawableIcon deliberately avoids resource-ID-only equality hiding restoration; native consumers support both drawable and invisible drawable. Independent from stock-list completion; user must add an available tile.")
            if target == "control_center_fix_tiles_list":
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="Latest source-only extension, no build/test/lint/device acceptance. Keep the six audited native editor candidates and conditionally append independent hyperextend_5g. Exact SystemUI 202602260/plugin 183022200; full 34-method QSTile Proxy contract, four main and two plugin factory callers. No NFC reuse, custom TileService or layout writes. MIUI binder controls only default-data SIM with owner/unlocked/airplane/SIM/UserManager restriction gates. Pin subscription/slot/value at tap; one short-lived intent, recheck before one write, no retries. Long press has a distinct non-network branch: exact enabled/exported/system Phone 5G Activity plus permission checks, native false-long-tap gate, owner/slot/user/lifecycle revalidation and ActivityStarter explicit owner UserHandle. Listening-only polling and generation invalidation; already-issued native work cannot be recalled. No full enterprise-policy parity claim.",
                    evidenceManifest="docs/five-g-tile-systemui-evidence.json",
                    pluginEvidenceManifest="docs/five-g-tile-plugin-evidence.json",
                    telephonyEvidenceManifest="docs/five-g-tile-framework-evidence.json",
                    contextRepairEvidenceManifest="docs/five-g-missing-context-evidence-2026-10-11.json",
                    settingsLaunchEvidenceManifest="docs/five-g-settings-evidence.json",
                    runtimeFeedback="User reports 5G absent in the installed 00:10 build. Repair replaces resource-user-context applicationContext check with deferred ActivityThread.currentApplication/package gate; same guard at binder access. Once-per-stage creation/rejection and candidate inclusion diagnostics added. Build/install coverage follows the hash-matched manifests; editor visibility and network toggle acceptance must be checked separately.",
                    referenceNote="Partial 5G preference mapping merged into the existing stock-list switch per user request; no separate feature/key. Upstream two icon styles and cellular-detail mode 3 are not migrated. Phone Activity presence is not captured current-device evidence; runtime capability guard does not imply device acceptance. Module never proactively rewrites saved layouts; host may remove an unavailable module tile after disable/safe mode. Native six remain stock-tiles evidence backed.")
            if target == "volume_long_press_expand":
                items[-1].update(status="partially_implemented_static_verified",
                    boundary="SystemUI 202602260/plugin 183022200 main-display ordinary collapsed volume panel only. Observe untransformed single-finger dispatch for a stationary 300ms hold. Slop/history/multi-touch, observed progress/geometry/configuration changes, lifecycle, stale or changed binding cancel. Preserve native drag until firing; send one native CANCEL then drain the remaining physical stream while hooks remain active and request the existing accessibility expand listener. Native screen-pinning/DND/animation policy retained. All-seven-hook readiness, shared loader discovery, weak view bindings and bounded main-thread callback. Global safe mode bypasses hooks; no seamless mid-stream recovery claim. No device acceptance.",
                    evidenceManifest="docs/volume-long-press-plugin-evidence.json",
                    systemUiEvidenceManifest="docs/volume-long-press-systemui-evidence.json",
                    remaining="Upstream removal of expand button and custom whole-panel 0.92 scale animation intentionally not migrated; native button/accessibility and native press animation retained. Control-center-embedded volume, secondary displays, touch exploration, independent-app panel and expansion-button-origin touches remain native. Bounded partial migration, not full upstream parity.")
            # 合并后这两个上游键映射到 slider_percentage 的子项；原位数值即 style=1。
            if target in {"slider_percentage.brightness", "slider_percentage.volume"}:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="Merged into the single slider_percentage card as its inline-value style (style=1); the old independent control_center_brightness_value/volume_value cards and their keys are removed with a v1->v2 migration (core/MergedCardMigration). Exact SystemUI 202602260/plugin 183022200 main-display main-panel native top text only; brightness uses normalized slider range and volume uses native discrete levels. Existing native labels including super-volume take priority. Restore still-owned plain text/visibility before native updates; equal native rich text is not overwritten. Weak bindings and unbind/destroy/detach cleanup; owned-only pre-draw observer also releases on disable/safety/hide without waiting for hook callbacks. No settings/audio writes, new overlays, layout/color/blur overrides or accessibility-label replacement. Queries resolve exactly; only concrete interception paths/callers are deoptimized. Scoped compile/unit/lint and fresh host-text validation linked separately, not device acceptance.",
                    evidenceManifest="docs/slider-value-plugin-evidence.json",
                    systemUiEvidenceManifest="docs/code-only-plugin-discovery-evidence.json",
                    repairValidationManifest="docs/mt-reconnect-validation-2026-10-11.json",
                    remaining="Secondary/side volume and brightness panels, mirror, secondary display, disabled/edit/accessibility/rich-text cases and overlay/custom-material variants are not migrated. Disable/safety cleanup occurs on the next pre-draw or detach, not synchronously. Main-panel/mirror/edit/accessibility capability changes still wait for native refresh; equal plain-text native writes cannot be distinguished by value ownership. No runtime lifecycle acceptance.")
            if target == "control_center_hide_edit":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    boundary="Source-only audit repair: resolve exact availability/context/distributor methods, require caller and shared discovery deoptimization, retain native availability first, then suppress only for enabled audited SystemUI/plugin versions. Dynamic setting reads; unavailable native entries never become available. No layout/list saves, capability bypass or forced redistribution. No build/test/lint/runtime validation in this batch; older successful builds do not include this repair.",
                    evidenceManifest="docs/edit-button-code-review-evidence.json",
                    systemUiEvidenceManifest="docs/code-only-plugin-discovery-evidence.json")
            if target and (target == "control_center_tile_corners" or target.startswith("control_center_tile_corners.")):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="Source-only partial migration; no compilation, tests, lint or device acceptance. Exact SystemUI 202602260/plugin 183022200 main-display default-theme non-card/non-detail small tiles with ordinary backgrounds only. Clone and mutate rectangular GradientDrawable before setting its per-instance radius; preserve native state colors unless independent color feature is enabled. Shared eight-hook readiness with tile background/icon color, one loader adapter and exact callers deoptimized. Native outline reads drawable radius without replacing global radius getter. Restore independently owned radius/background color before native updates and on recycle; preserve newer native/transition shape writes. No resource/shared ConstantState mutations.",
                    evidenceManifest="docs/tile-icon-plugin-evidence.json",
                    stateEvidenceManifest="docs/tile-icon-systemui-evidence.json",
                    systemUiEvidenceManifest="docs/code-only-plugin-discovery-evidence.json",
                    remaining="Material/blend/glass, card and detail tile variants remain native; no forcing material off. Radius is upstream raw pixels 1..99/default 72 clamped to half tile size. Native transition setter may overwrite the custom radius until a new background is created. Detached views without display context may wait for native background creation. Disable recovery waits for native refresh; safe mode/old view cleanup may require restart. No full-upstream shape/transition parity claim.")
            if target and (target == "control_center_tile_color" or target.startswith("control_center_tile_color.")):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="Source-only partial migration; no compilation/tests/lint/runtime acceptance. Exact SystemUI 202602260/plugin 183022200 main-display default-theme ordinary small tiles, state 2 and activeBgColor 0 only. Do not apply new custom colors to policy-disabled, transient, restricted or warning states. Clone active rectangular GradientDrawable before changing a non-stateful solid background color. For generic exact VectorDrawable/AnimatedVectorDrawable icons only, temporarily override per-view defaultIconColor around native drawableTint and restore it in finally without overwriting a newer native write. Native mutation/tint and animation identities/callbacks remain; no global resource or shared ConstantState writes. Background/icon inputs independently blank/invalid follow native; opaque RGB only. Shared eight-hook adapter with optional radius setting retains background ownership and native touch/accessibility.",
                    evidenceManifest="docs/tile-icon-plugin-evidence.json",
                    systemUiEvidenceManifest="docs/tile-icon-systemui-evidence.json",
                    remaining="Classic-QS color and complete material parity remain pending. Card colors are tracked by the independent control_center_card_tile_color adapter, not covered by this small-tile feature. Material/glass, detail/card tiles and stateful/filtered backgrounds remain native here. Seven special icon specs, third-party custom tiles, bitmap and other drawable types are excluded. Contrast is user responsibility. Background enable waits for native creation and disable for refresh. Icon changes/disable wait for actual native drawableTint; same-state early returns can retain old tint. Safe-mode cleanup may require restart. No full upstream color parity or immediate recovery claim.")
            if key == "prefs_key_system_ui_control_center_qs_color":
                items[-1]["referenceNote"] = "Partial icon-color migration, not upstream global qs_icon_enabled_color replacement. Scoped per-view input only during native tint; preserve quietmode/papermode/mute/cell/autobrightness/flashlight/batterysaver special colors and custom/bitmap icons. No forced retint or vector replacement; same-state native caching can retain old color until a real tint call."
            if target == "control_center_auto_collapse":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    boundary="Source-only audit repair; no compilation/tests/lint/runtime acceptance. Exact SystemUI 202602260, main-thread display-0 context, dynamic switch, known usable tile states 1/2, non-policy/non-transient, nonempty non-edit spec, no shown detail and status-bar state 0 before and after native click dispatch. Resolve eleven exact methods and nine fields; deoptimize concrete target/collapse/eight actual main-package callers, resolve abstract status query without deoptimization. One click hook with same-tile nested guard and host-identity recheck. Preserve native click/handler/authentication/rejection/exception paths, then request native panel collapse; dispatch is not operation success. No module asynchronous task or settings writes.",
                    evidenceManifest="docs/auto-collapse-systemui-evidence.json",
                    remaining="Main-package dispatch xref is complete; zero plugin dex references do not prove runtime absence, so current plugin coverage is unconfirmed. Missing display context fails native. Native collapse is a scheduled coroutine and cannot be cancelled by later disabling this switch. Tile execution can still reject or fail after dispatch; do not interpret panel collapse as success.")
            if target == "volume_hide_collapsed_footer":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    boundary="Source-only audit repair; no compilation/tests/lint/runtime acceptance. Exact SystemUI 202602260/plugin 183022200 main-display ordinary volume dialog; preserve embedded and independent app-volume panels. Require both hooks and exact five caller paths plus shared discovery deoptimization. Restore only owned GONE visibility before native update/expand callbacks, then suppress only currently visible collapsed footer. Never synthesize visibility from a cached requested boolean. Leave expand button, native active-stream/tiny-screen restrictions, audio/DND state and native insets code intact. Weak View-to-Int ownership; closing switch waits for next native callback, safe-mode cleanup may require rebuild/restart.",
                    evidenceManifest="docs/volume-footer-plugin-evidence.json",
                    systemUiEvidenceManifest="docs/code-only-plugin-discovery-evidence.json")
            if target and (target == "classic_qs_layout" or target.startswith("classic_qs_layout.")):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="2026-10-11 source-only continuation on SystemUI 202602260 main display; no compilation/tests/lint/device acceptance. Expanded columns applied to all existing/new pages before native capacity/measurement, with native maximum and 48dp minimum touch width plus padding/margin. Scoped resource-column inputs restored in finally; native effective mColumns stays coherent for measurement/layout/accessibility. Redistribute only for changed columns/cap. First-page row cap and orientation-specific collapsed prefix retained, no saved tile/order edits or automatic classic-style selection. Four-hook all-ready gate and exact caller deoptimization.",
                    evidenceManifest="docs/classic-columns-evidence.json",
                    remaining="Native width/height constraints can reduce requested rows/columns/count. Disable restores expanded geometry on next measure, collapsed count on native resource refresh; safe-mode layout cleanup can require rebuild/restart. No device acceptance.")
            if key in {"prefs_key_system_control_center_old_qs_columns", "prefs_key_system_control_center_old_qs_columns_horizontal"}:
                items[-1]["referenceNote"] = "Migrated through pre-distribution updateColumns inputs, not upstream QSGrid's post-layout mColumns mutation. New pages created during pager measurement inherit the same effective width limit."
            if target == "lockscreen_charging_info.interval":
                items[-1]["referenceNote"] = "Uses the XML preference and preserves half-second values. Upstream polling reads a mismatched statusbar-prefixed key and integer-divides before multiplication; those defects are not copied."
            if target == "media_card_always_dark":
                items[-1]["boundary"] = "OS4 202602260 native notification media only. Seven material-effect branches use scoped night resource contexts; foreground update restores controller context in finally. Native AOD listener, transition guards, clear/apply, seekbar visibility and playback remain. Tiny screen and island unchanged. No custom artwork/gradient backgrounds or device acceptance. Safe-mode restoration retains newer native background writes."
                items[-1]["evidenceManifest"] = "docs/media-dark-systemui-evidence.json"
            if target and target.startswith("media_card_background."):
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    boundary="Source-only OS4 202602260 main-display ordinary unlocked notification media. Current ColorScheme(WallpaperColors,boolean,int), CONTENT=6 and complete 13-shade palettes; four background modes, exact upstream linear inversion threshold 192, owned text/actions/seamless and level0/1 seekbar colors. Cover and palette share a bounded 333ms interrupted-fade timeline. Mode0 ambient uses a removable view overlay, preserves native material, moves only while playing/visible/animations enabled; optimized sampling uses Monet, default uses bounded local dominant-color bins. Shared binding/generation/detach/holder/safe-mode restoration, no host-art mutation. No build/tests/device acceptance.",
                    remaining="Level1/2 native shader passes use a synchronous MODULATE Paint filter only for bound custom-art cards, preserving existing non-null filters, host shader/texture/alpha/geometry and restoring by identity in finally. Missing art now uses only bindMediaData's already user-resolved appIconDrawable with userId/appUid/package identity checks; no new PackageManager/Icon reads. User/package switches clear old pixels/palette rather than cross-fade. Keyguard/AOD/tiny-screen/island, upstream AGSL pixels and resize animation remain outside this adapter. Not full upstream visual parity or device acceptance.",
                    fallbackEvidenceManifest="docs/media-fallback-evidence.json",
                    evidenceManifest="docs/media-palette-evidence.json",
                    lifecycleEvidenceManifest="docs/media-background-systemui-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md")
            if target == "media_card_background.transition":
                items[-1]["referenceNote"] = "Extended from cover-only to synchronized Monet foreground/background transitions, including scoped native shader-pass tint. Current extension is unbuilt, not covered by historical tests. Default off; bounded thumbnail job and native-animation visibility gates."
            if key == "prefs_key_system_ui_control_center_media_control_background_mode":
                items[-1]["referenceNote"] = "OS4 uses MediaViewBinder -> mediaViewEffectsMap -> seven native MediaView*Effect branches, not upstream updateMediaBackground. Current local rendering is deliberately partial with documented visual/palette and lockscreen boundaries. Do not promote this row or the whole media page to complete."
            if key == "prefs_key_system_ui_other_default_plugin_theme":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    boundary="Source-only audit repair, no compilation/tests/lint/runtime acceptance. Exact SystemUI 202602260/plugin 183022200 main-display contexts, all-four-hook and shared discovery readiness; deoptimize three immediate scopes and their three actual outer callers. Native ThemeUtils query runs first, dynamic settings and thread-local nested scope control only the three volume material branches. Keep low-end/blur/advanced-material gates and native resource selection. No shared theme field writes or global control-center theme override. Disable restores queries immediately while hooks active but displayed style waits for native refresh/rebuild; not a complete third-party skin replacement.",
                    evidenceManifest="docs/volume-theme-code-review-evidence.json",
                    systemUiEvidenceManifest="docs/code-only-plugin-discovery-evidence.json")
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
            if (items[-1].get("target") or "").startswith("status_bar_battery_style."):
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/battery-style-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only current SystemUI202602260 main-display repair. Four exact nonstatic refresh signatures and four typed instance fields replace first-method/name-only matching; all four hooks and twelve dispatch callers gated together. Native font/theme/battery callbacks run once. Bold retains native font family. Hidden or absent percent mark transfers trailing padding to the number independent of hook ordering; mark default27 preserves native baseline. Stored geometry clamped to catalog bounds and finite density. Existing sibling-group swap and hiding precedence retained.",
                    remaining="Config remains load-time immutable; disable/change/safe-mode full restoration requires user restart, not live reparent restoration. AOD/island geometry and native LayoutTransition rendering still require runtime review. Three new pure-policy/signature tests are defined but not run. Four formerly repaired records now explicitly unbuilt because source changed; historical validation does not cover this repair.")
            if target == "status_bar_double_tap":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/statusbar-double-tap-current-evidence.json",
                    frameworkEvidenceManifest="docs/statusbar-double-tap-framework-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Source-only repair replaces setOnTouchListener takeover with exact PhoneStatusBarView.onTouchEvent after-native observation. Native return/handler/region/dispatch interactive gate and child gesture ownership retained. Main-thread/main-display/current-version attached shown finger-only unobscured accepted touch; history drag, multi-touch, exploration, cancel, detach/configuration changes reset weak per-view tracker. Dynamic parent disable and safety block before action. Exact nonstatic PowerManager.goToSleep(long):void; no installation-time service reads, event consumption/replay or background sleep task.",
                    remaining="Native-accepted background touch only; no expansion of native hit regions or gesture availability. Legacy installed listener is removed only by user host restart; no hot reload or power action was performed. Cached PowerManager declaration is not new device evidence. Three added policy/signature tests unexecuted; dispatch/render/power acceptance and unified build remain open.")
            if (items[-1].get("target") or "").startswith("status_bar_network_speed."):
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/network-speed-current-evidence.json",
                    counterEvidenceManifest="docs/network-speed-loopback-evidence.json",
                    repairValidationManifest="docs/partial-feature-repair-validation-2026-10-11.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="SystemUI202602260 seven exact hook choices, twelve method/caller signatures and six typed fields. Common readiness, UI/background looper and dynamic parent gates. Default unmodified data avoids sampling and does not resolve extra counter APIs. Custom sampling resolves the exact static TrafficStats String-to-long loopback queries demonstrated by the complete cached native handler; calls happen only at runtime. Subtract lo deltas independently from Tx/Rx; reject unavailable, reset, incoherent and sleep samples, including lo-only counter reset. Never clamp an invalid read to zero or reuse stale custom speed after read failure. Valid intervals alone produce custom two-slot data; per-direction subtraction and saturating aggregation prevent overflow. Unit hiding follows actual empty second slot and restores native fallback units. Default alignment retains native gravity; inputs bounded to catalog. Background reschedule only changes an already pending SAMPLE on the exact native bg handler and never recreates stopped loops.",
                    remaining="Upstream all_status_enable is the module installation parent gate, not an all-placement request; its row maps to the real local parent. Native text/layout animation and fixed-width/container measurements remain unaccepted; line spacing is the upstream stored/20 multiplier. Sampling still occurs at UI updateText rather than using the exact native background snapshot; four counter reads are not atomic, so invalid samples retain native output. Standalone TrafficStats implementation bodies are not present in the cached framework.jar; counter evidence is current-host call-site evidence, not fresh framework capture. Font/layout and full safe-mode restoration require restart; interval returns to native at the next scheduled callback. Scoped repair validation is recorded separately; no device acceptance or full upstream parity.")
            if key == "prefs_key_system_ui_statusbar_network_speed_all_status_enable":
                items[-1].update(status="code_reviewed_repair_unbuilt", target="status_bar_network_speed",
                    evidenceManifest="docs/network-speed-current-evidence.json",
                    boundary="Actual upstream SystemUIV.java/SystemUIB.java install gate for NewNetworkSpeed, NewNetworkSpeedStyle, NetworkSpeedSpacing and NetworkSpeedSec, not a separate all-status placement feature. Local parent already gates those adapters in HookDispatcher and runtime Access.enabled. Corrects the prior unsupported interpretation; no extra or empty switch added.",
                    remaining="Existing network-speed adapter residuals remain unchanged: native sampling parity/geometry/runtime acceptance, snapshot restoration requires restart. Source-only; no current compilation.")
            if key == "prefs_key_system_ui_status_bar_hide_smart_strong_toast":
                items[-1].update(status="code_reviewed_partial_unbuilt", target="status_bar_hide_strong_toast",
                    evidenceManifest="docs/strong-toast-current-evidence.json",
                    boundary="Current DeviceNotificationListenerImpl setStatus strong_toast_action parses island_param before synchronous handleDeviceNotification. Identity-bound nested ThreadLocal scope suppresses only that parsed external delivery. Full CommandQueueDelegate caller; no global pre-draw cancellation, window hiding, active notification removal or internal charge animation/cleanup changes. Main-display/current-version/live-setting and common-ready gates, no eager services.",
                    remaining="Only current external strong-toast command route; internal charge and other island producers stay native. Existing prompts expire normally, disable affects next delivery. Test short/long actions are separate unresolved action rows, not switches. Four complete current members; pure scope/signature tests defined but not executed, no device acceptance.")
            if key.startswith("prefs_key_system_ui_island_media_control_"):
                suffix = key.removeprefix("prefs_key_system_ui_island_media_control_")
                island_targets = {
                    "media_button_layout_switch": "island_media_layout",
                    "media_button_actions_left_aligned": "island_media_layout.left_aligned",
                    "media_button_hide_seamless": "island_media_layout.hide_seamless",
                    "media_album_mode": "island_media_layout.album", "media_button_mode": "island_media_layout.order",
                    "title_margin": "island_media_layout.title_margin", "title_padding": "island_media_layout.artist_margin",
                    "media_button": "island_media_layout.button_size", "media_button_custom": "island_media_layout.custom_button_size",
                    "media_button_size_switch": "island_media_text", "title_size": "island_media_text.title",
                    "artist_size": "island_media_text.artist", "time_view_text_size": "island_media_text.time",
                    "progress_on": "island_media_progress", "progress_mode": "island_media_progress.mode",
                    "progress_thumb_mode": "island_media_progress.thumb", "progress_corner_radius": "island_media_progress.corner",
                    "progress_thickness": "island_media_progress.height", "progress_comet": "island_media_progress.comet",
                    "progress_round": "island_media_progress.round",
                }
                if suffix in island_targets:
                    is_progress = suffix.startswith("progress_")
                    items[-1].update(status="code_reviewed_partial_unbuilt", target=island_targets[suffix],
                        evidenceManifest="docs/island-progress-current-evidence.json" if is_progress else "docs/island-media-current-evidence.json",
                        codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                        boundary=("Independent current island Binder primary/dummy Holder pair; not the ordinary media controller. Same actual HyperProgressSeekBar class, exact attach/detach/observer/draw and complete caller signatures. Preserve native SeekBar/range/listeners/RenderState/session/accessibility; main display, awake/unlocked, native pressed/dragging drawing. Native height temporarily changed only during draw; custom wave/line/comet/thumb use actual theme time color on shader level2, not no-op primary setters. Native playing/listening/scrubbing and system animation gate, no polling jobs; weak identities and default-off independent settings."
                            if is_progress else
                            "Independent current island Binder/Holder and exact lifecycle/callers. Native reset creates both holders, loads one ConstraintSet and applies it twice; scoped load copies that template, edits verified current XML identities, restores original field in finally. No duplicate apply/child/listener/session replacement. Owned text sizes, app/album/seamless visibility and drawable matrices restore before native updates and on detach/disable/safety; native takeover becomes new baseline. Real/dummy pairs both handled. SP matches actual upstream setter, not XML dp label. Artist default2dp preserves native despite upstream hook4dp mismatch. Icon size affects drawing only; semantic middle three slots are main, other/notification slots custom."),
                        remaining=("Source-only rendering, touch/accessibility, RTL and AOD transition parity remain unaccepted. Native mode0 level0/1 primary geometry now uses a synchronous exact getter scope; see docs/media-native-height-evidence.json. No full upstream shader/visual parity; the six background rows have a separate source-only adapter, not acceptance. First enable requires restart; disable next draw is native."
                            if is_progress else
                            "Layout/order/margins are fixed until native holder reset/user restart, including full safety restoration; dynamic owned styling cleanup does not claim to restore constraints. The six background rows have an independent source-only adapter; AOD styling remains native. Semantic prevOrCustom/nextOrCustom occupy main slots even if the app supplies a custom action; no guessed locale-label role. Eight policy/signature test definitions shared with strong-toast/progress are not executed. No build/device acceptance. Upstream text switch XML/hook names disagree; local switch is actually wired."))
                island_background = {
                    "background_mode": "mode", "control_color_anim": "transition",
                    "inverse_color": "inverse", "panel_background_blur": "blur",
                    "ambient_light": "ambient", "ambient_light_opt": "optimized",
                }
                if suffix in island_targets and suffix.startswith("progress_"):
                    items[-1]["supplementalEvidenceManifests"] = ["docs/media-native-height-evidence.json"]
                if suffix in island_background:
                    items[-1].update(status="code_reviewed_partial_unbuilt",
                        target="island_media_background." + island_background[suffix],
                        evidenceManifest="docs/island-background-current-evidence.json",
                        codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                        boundary="Independent current island Binder primary/dummy Holder, complete MusicBgView/MusicBgShader lifecycle and identity evidence. Retains native shader child, synchronization, parent transforms, callbacks and session; only owned parent background and shader-child alpha. Four bounded artwork/Monet modes; native-mode ambient 0 native/1 hide/2 custom, not the upstream mismatched ambient_light_type key. Owned text/action/time/seamless and actual seekbar colors, per-draw high-end shader Paint MODULATE with finally restore. Shared artwork job/generation and ambient clock, interrupted cover/palette fades, runtime awake/unlocked/main-display gates. Disable/safety/hidden/detach restores only owned state. No native shader or service initialized at installation.",
                        remaining="Source only, six new tests defined not executed. Native shader lifecycle remains alive even when visually replaced; no claim of power saving, exact native AGSL or all pause/resize/pull-down transition equivalence. Bounded sampled artwork and Monet differ from upstream processors/MiPalette. Native RenderEffect/contrast/interactions need device acceptance. Initial enable needs native attach/restart. Historical606 does not cover this code.")
            current_target = items[-1].get("target") or ""
            if key == "prefs_key_security_center_gms_open":
                items[-1].update(status="code_reviewed_partial_unbuilt", target="control_center_gms_tile",
                    evidenceManifest="docs/gms-tile-systemui-evidence.json",
                    settingsEvidenceManifest="docs/gms-settings-evidence.json",
                    repairValidationManifest="docs/migrated-feature-review-validation-2026-10-11.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Independent full-current QSTile Java proxy, exact factory/callers, additive classic/plugin editor candidate; no saved-spec writes or repurposed ScreenLockTile. Ten-package fixed family, existing owner-user packages only. Explicit unlocked non-false tap, permission/restriction gates, 1500ms generation-bound intent and separate5s transaction budget. Compare full name/UID/version/raw/effective-enabled snapshots before and after writes; final whole-snapshot check includes already-target packages and final authorization. Failure compensation touches only still-owned complete expected values and stops on revocation. SETTING actions never enter the package transaction branch. Optional SecurityCenter GmsCoreSettings long-click verifies exact system/enabled/exported/permitted Activity, native isFalseLongTap and final user/lifecycle/age gates; main-thread native ActivityStarter gets explicit owner UserHandle. Known inert refactor and unknown starter implementations are not offered. Missing optional reflection or target keeps ordinary toggle available; no installation-time lazy service invocation. Listening-only refresh, copied callbacks and complete34-method interface retained.",
                    remaining="No package operation, Activity launch or runtime acceptance executed. SecurityCenter target presence has not been captured; MT endpoint refused connection, which is not proof of absence. Eight complete cached native launch-contract members support the guarded source route, not device functionality. PackageManager operations and final reads are not atomic; later external changes cannot be excluded, and revocation/time expiry can prevent compensation. Completed explicit user actions are not undone on module disable. Unknown/inert ActivityStarter backends remain unavailable. Scoped build/test/lint results and three pre-fix failure reproductions are recorded separately; no ledger promotion or full migration completion.")
            if key in ICON_SLOT_SOURCE_REPAIRS:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/icon-block-list-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Current native default-list slot and exact setter/consumer evidence. Per-instance copied block list plus owned ignoredSlots delta; no installation-time static reads or shared static-list mutation. HOME/KEYGUARD/QS_FAKE mirror status-bar policy and QS uses control-center policy. Modern view setBlocked is a no-op; actual parent ignoredSlots filtering is also updated. Native incremental adds restore only the owned previous list before applying new input. Minimalism, user hide lists, icon availability, animation and island geometry stay native.",
                    remaining="Bounded display-list policy, not forcing icons whose native producer suppresses them. Dynamic disable restores owned lists on the next native list callback, not proactively; settings with no installed hooks and safe-mode full restoration need host restart. Other catalog aliases, alarm timing and Wi-Fi/mobile reordering retain independent audit states. No build or runtime acceptance.")
            if current_target.startswith("status_bar_icons.battery_"):
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/battery-visibility-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Four exact current refresh signatures, twelve dispatch callers and eight typed fields; all selected hooks common-ready, main-thread/main-display/version gated. Solid/hollow/digital icon and charging hiding uses actual current fields, percentage/mark composition retains zero-size precedence. Removed the nonexistent legacy digit field from policy, not a user feature.",
                    remaining="Settings are load-time immutable like battery customization; disable/change/safe-mode full layout recovery requires user restart. AOD/island geometry, transitions and visual accessibility need runtime review. Previous three repaired records are explicitly unbuilt again; historical606 does not cover changed source.")
            if current_target in {"status_bar_icons.wifi_activity", "status_bar_icons.wifi_standard"}:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    evidenceManifest="docs/wifi-indicator-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Real current bind plus native activity/standard render callbacks. No repository constructor hook, eager ACTIVITY_DEFAULT read, concrete Flow replacement or Unsafe field write. Native collectors, cached theme resource IDs, tags, data, subscriptions and cancellation remain intact. Owned weak per-view visibility hides only the chosen child, captures fresh native state, and restores on detach or disabled/safety pre-draw if still owned. Rebind restores before native cached initialization.",
                    remaining="Standard always-show remains unimplemented: current raw standard calculator feeds a separate <=5-to-zero mapping, not the old named function. Activity/standard hiding is main-display version-bounded and not pixel/lifecycle device accepted. Full legacy Flow restoration needs restart, no hot reload performed. New tests are definitions only.")
            if current_target.startswith("status_bar_icons.notification_icon_max"):
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/notification-icon-limit-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Copy exact NotificationIconsViewData before its real collector computes difference and binding failures. Preserve native list/order/filtering and MaximumAmount/MaximumIndex, including failure accounting and native zero suppression. Match both collector and container statusbar[0] roles plus actual display/version/thread. No post-setter mMaxIcons overwrite, no AOD/shelf override, shared VM edit or added coroutine.",
                    remaining="Disable returns native on next data emission or rebind; cached layout is not immediately forced. Native island width/overflow and flip-screen controls remain active. No build/render/lifecycle acceptance; changed semantics intentionally preserve native suppression.")
            if current_target == "status_bar_icons.wifi_standard":
                items[-1].update(
                    evidenceManifest="docs/wifi-standard-current-evidence.json",
                    hidingEvidenceManifest="docs/wifi-indicator-current-evidence.json",
                    frameworkEvidenceManifest="docs/force-blur-framework-evidence.json",
                    boundary="Default/show/hide are wired under the existing key. Show recovers only current mapper-discriminant2 native raw4/5 values zeroed by the <=5 filter. Original producer retains enabled/forceHidden/metered/active/slave/carrier-merged and native chipset mapping gates. Temporarily substitute an assignable FlowCollector interface bridge, never a concrete Flow field: preserve original mapper/continuation, exactly one downstream emission and unchanged suspension result. Finally restore only owned delegate; bridge becomes inert even if restore fails. Exact resume and collect signatures; runtime-only application/version lookup. Hide remains the weak main-display native-render adapter.",
                    remaining="Always-show changes the process-shared Wi-Fi view model, including its native activity placement consumers; it is not main-display-only like hiding. Switching modes/first enable and cached initial state need host rebuild; disabling show takes effect at next native emission, full safe-mode/cache recovery may require restart. Coroutine bridge, initial frame and multi-display rendering are not runtime accepted. Fifteen current MT members plus one separately identified cached framework declaration, new unexecuted policy/signature tests; no current compilation.")
            if current_target in {"status_bar_mobile.hide_roaming", "status_bar_mobile.hide_indicator", "status_bar_mobile.hide_vowifi"}:
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/mobile-markers-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Exact current merged emitters and discriminants, not field-name Flow substitution. Change only Boolean or host Pair display input, preserving native allowed-update gates and animator-container writes. Roaming's first-or-second writer gate is retained when replacing visibility; speech-HD, satellite, special5G, type and no-service discriminants remain native. Same live per-SIM collectors/cancellation with main-display/version/thread gates. No OperatorConfig write for VoWiFi and no incompatible concrete field writes.",
                    remaining="Next valid native emission or rebind restores display after disable; no proactive replay of stale per-SIM inputs. Safe-mode and legacy replaced-Flow complete recovery requires restart. First-frame, dual-row composition, SIM churn and rendering remain unaccepted; the module never changes SIM/network/call settings.")
            if current_target == "status_bar_mobile.hide_volte":
                items[-1].update(status="code_reviewed_repair_unbuilt",
                    evidenceManifest="docs/operator-icons-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="The current OperatorConfig has no declared constructor. Hook the complete actual getMiuiOperatorConfig(int) producer after its sole fresh-object return instead. Set only hideVolte on an unpublished exact-type display configuration; preserve all other carrier fields and native calculations. Four complete dispatch callers and real hideVolte readers, inert install-time field resolution, runtime context/version and live option checks. No cached config iteration, telephony/IMS/network/SIM write or service initialization.",
                    remaining="Native display configuration is intentionally process-wide, not main-display-only. Cached configs, HD visibility map and Flow equality update through native lifecycle; disable/safety may need user restart for full recovery. No device acceptance or current compilation.")
            if key == "prefs_key_system_ui_status_bar_hide_icon":
                items[-1].update(status="code_reviewed_repair_unbuilt", target="status_bar_screenshot_hide",
                    evidenceManifest="docs/screenshot-layer-cached-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Cached screenshot10603038 and framework DEX evidence, not freshly confirmed installed-host evidence. Exact d1.d capture strategy, full DisplayCapture caller and ScreenCaptureInternal builder return/static signatures. Append StatusBar only to final per-call exclusions on display0, skip RearScreenshotWindow and keep array identity when already excluded. Preserve existing exclusions/order/duplicates and all original capture/crop/secure/protected/buffer lifecycle. No live view/surface mutation or capture initiated by module. Other strategies stay native.",
                    remaining="Ten complete cached declarations/code hashes and exact interface caller scan; not current device acceptance. Unified compilation/tests/lint and runtime capture acceptance remain open. Disable follows next call; no persistent layer state to restore.")
            if key.startswith("prefs_key_system_ui_statusbar_battery_detail_"):
                suffix = key.removeprefix("prefs_key_system_ui_statusbar_battery_detail_")
                if suffix == "at_right":
                    items[-1].update(
                        evidenceManifest="docs/battery-detail-current-evidence.json",
                        remaining="Right-side independent slot remains unimplemented, not removed from requirements. Current MiuiStatusBatteryContainer and MiuiBatteryMeterView measure/layout only named fields; adding a plain child is ineffective, while MiuiStatusIconContainer requires StatusIconDisplayable. No placeholder right-side switch is exposed. Need a current native slot/holder lifecycle adapter, not old fake type91 constructors.")
                else:
                    items[-1].update(status="code_reviewed_partial_unbuilt",
                        target="status_bar_battery_detail" + ("" if suffix == "enable" else "." + suffix),
                        evidenceManifest="docs/battery-detail-current-evidence.json",
                        codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                        boundary="Current main-display unlocked status-bar left clock parent only. Owned separate TextView, no native reparent/text/tag/Flow/sysfs mutation. Complete current inflate/field/XML identity; native clock color and font, 17 real settings for formatting, charging gate, geometry and 1-10s sampling. Lazy bounded worker with expiring idle thread, one in-flight request per binding, stale/generation rejection; hide/detach/lock/sleep/disable/safety stop sampling. BatteryManager microamps uses its positive-charging contract; voltage/temperature from read-only sticky broadcast. Invalid values omitted, zero retained, power is battery-side estimate.",
                        remaining="Right independent slot not implemented. Native width/cutout/island/notification interactions, accessibility, hardware current polarity/ratio and runtime acceptance remain open. View configuration is load-time; first enable/config changes need restart. No compilation/tests/lint/device acceptance.")
            if key == "prefs_key_system_ui_status_bar_battery_style_change_location":
                items[-1].update(evidenceManifest="docs/battery-order-current-evidence.json",
                    boundary="Actual source repair: current MiuiBatteryMeterView onLayout/onMeasure uses named fields, not child order, so remove ineffective child detach/re-add. After the exact native onLayout, swap only horizontal icon/percent bounds for main-display HOME state0/style3, preserving sizes, vertical bounds, gap, IDs and parentage. Hidden, overlapping, separate charging glyph, transformed/animated/AOD/island/CC-expanding states remain native. No native field writes or new animator.",
                    remaining="Closing switch/native takeover restores at next native layout; full legacy reordered hierarchy and safety recovery require restart. Bounded ordinary style3 implementation, not island/AOD/animation parity. Full fixture and geometry tests defined, not run.")
            if key == "prefs_key_system_ui_status_bar_swap_wifi_and_mobile_network":
                items[-1].update(status="code_reviewed_partial_unbuilt", target="status_bar_signal_order",
                    evidenceManifest="docs/signal-order-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Current factory returns a fresh unpublished StatusBarIconList; the old string-array constructor is absent. Reorder the five existing wifi/demo_wifi/no_sim/mobile/demo_mobile slot objects in place before publication. Require exact original order, unique names and no holders/subslots; preserve all other slots, the unmodifiable view backing list, native per-SIM order and all view/controller lifecycle. Exact complete factory and Dagger caller plus resource array46. No duplicated hotspot/airplane or speculative slave_wifi slot.",
                    remaining="The native list is shared, not main-display-only. Enable/disable/config/safety requires native reconstruction/user restart. Rendering across panels/displays/demo/dualSIM, compilation and runtime acceptance remain pending.")
            if key in {"prefs_key_system_ui_status_bar_icon_alarm_clock", "prefs_key_system_ui_status_bar_icon_alarm_clock_n"}:
                items[-1].update(status="code_reviewed_partial_unbuilt",
                    target="status_bar_icons.alarm_clock" + ("_hours" if key.endswith("_n") else ""),
                    evidenceManifest="docs/alarm-window-current-evidence.json",
                    listEvidenceManifest="docs/icon-block-list-current-evidence.json",
                    codeReviewManifest="docs/migration-continuous-progress-2026-10-11.md",
                    boundary="Existing mode0/1/2/11/12 list policy retained; new mode3 and 0-48h setting filter only the native isIconVisible result for current main-display alarm_clock views. Capture existing UserTracker from the native alarm controller without initializing it. Read current-user locked_screen_alarm_status epoch (actual current native observer contract), with AlarmManager nextAlarmClock fallback, never parse locale weekday strings or invent next-week timestamps. Native false/list/minimalism gates win. Attached shown ancestors only, bounded worker,30s read cadence,35s sample bound,59s threshold tolerance, user/generation invalidation, native remeasure on disable/detach/safety. No icon fields/visibility/Flow writes, alarm schedules, provider writes or new receiver.",
                    remaining="Missing/unreadable/stale/user-mismatched timing keeps native icon visibility; no display before native UserTracker capture. Cancel/reschedule may wait up to30s unless native icon already hides; keyguard alarm text remains native. First enable and full safety recovery require restart; fixtures/tests defined but unexecuted, no current build/device acceptance.")
    # Current source-only continuation is deliberately outside the historical build gate.
    for item in items:
        target = PENDING14_SOURCE_ROWS.get(item["key"])
        if target:
            item.update(status="code_reviewed_partial_unbuilt", target=target,
                evidenceManifest=PENDING14_EVIDENCE.get(target, "docs/window-decoration-evidence.json"),
                codeReviewManifest="docs/pending14-continuation-validation-2026-10-11.md",
                boundary=PENDING14_BOUNDARIES.get(target, "Default-off current-host bounded implementation. WMShell uses the root-extracted implementation JAR with SDK37/hash gating, not its resource APK; Taplus uses its current signature-protected native provider, not the obsolete Settings.System switch. Shared native-toggle lifecycle preserves independent specs, editor candidates, user/permission/lifecycle fencing and native launch routing. Precise limits remain in pending14-migration-2026-10-11.md."),
                remaining="No runtime acceptance. Native rendering, user/lifecycle races, provider side effects and recovery remain unaccepted; six other original rows remain pending. High/custom sunlight and live flashlight-slider behavior are not supplied by the bounded replacements. Local verification is recorded only if the matching-input validation gate below passes.")
    # Rows unblocked by the remaining-6 carrier audit map onto verified current carriers.
    for item in items:
        resolved = PENDING14_RESOLVED_ROWS.get(item["key"])
        if resolved:
            target, evidence, boundary, remaining = resolved
            item.update(status="code_reviewed_partial_unbuilt", target=target,
                evidenceManifest=evidence,
                codeReviewManifest="docs/pending14-remaining6-carrier-audit-2026-10-11.json",
                boundary=boundary, remaining=remaining)
    # Source-only batches were subsequently built and tested together. Preserve
    # their original review boundaries instead of treating installation as device acceptance.
    validated_features = {
        "classic_qs_layout", "control_center_tile_corners", "control_center_tile_color",
        "slider_percentage.brightness", "slider_percentage.volume",
        "control_center_hide_edit", "control_center_auto_collapse",
        "volume_hide_collapsed_footer", "volume_default_theme",
        "control_center_fix_tiles_list",
    }
    validation = Path("docs/five-g-context-validation-build-2026-10-11.json")
    if not validation.is_file():
        validation = Path("docs/migration-validation-build-2026-10-11.json")
    if not validation.is_file():
        validation = Path("docs/migration-validation-build-2026-10-10.json")
    validated = json.loads(validation.read_text(encoding="utf-8")) if validation.is_file() else {}
    inputs = validated.get("sourceInputsSha256", {})
    test_count = validated.get("tests", {}).get("tests", 0)
    install_manifest = validated.get("installManifest", "docs/migration-validation-install-2026-10-10.json")
    validation_matches = bool(inputs) and all(
        Path(name).is_file() and source_digest(Path(name)) == digest
        for name, digest in inputs.items()
    )
    validation_matches = validation_matches and all(
        path.as_posix() in inputs for path in Path("app/src").rglob("*") if path.is_file()
    )
    if validation_matches:
        for item in items:
            if (item.get("target") or "").split(".")[0] not in validated_features:
                continue
            previous = item["status"]
            if previous not in {"code_reviewed_partial_unbuilt", "code_reviewed_repair_unbuilt"}:
                continue
            item["previousStatus"] = previous
            item["status"] = ("partially_implemented_static_verified" if previous == "code_reviewed_partial_unbuilt"
                              else "repaired_static_pending_device")
            item["initialCodeReviewBoundary"] = item.pop("boundary")
            item["boundary"] = f"Subsequent cumulative validation: {test_count} local unit tests and R8 Release passed; full Debug/Release lint has zero errors. Original feature exclusions and restoration limits in initialCodeReviewBoundary still apply. Installed package hash verified; no assistant-requested host restart or unified device acceptance."
            item["buildManifest"] = str(validation).replace("\\", "/")
            item["installManifest"] = install_manifest
    audit_manifest = Path(REMAINING_ROW_AUDIT_MANIFEST)
    audit_rows = {}
    if audit_manifest.is_file():
        audit_rows = {row["key"]: row
                      for row in json.loads(audit_manifest.read_text(encoding="utf-8"))["rows"]}
    for item in items:
        audited = REMAINING_ROW_AUDIT.get(item["key"])
        if not audited or item.get("target"):
            continue
        if item["status"] not in {"needs_audit_existing_partial", "pending"}:
            continue
        row = audit_rows.get(item["key"])
        item["previousStatus"] = item["status"]
        item["status"] = audited
        item["remainingRowAudit"] = REMAINING_ROW_AUDIT_MANIFEST
        if row:
            item["hostCarrier"] = row["hostCarrier"]
            item["boundary"] = row["boundary"]
            item["determination"] = row["determination"]
            if row.get("remaining"):
                item["remaining"] = row["remaining"]
    pending14_manifest = Path("docs/pending14-unified-validation-2026-10-11.json")
    pending14_validation = json.loads(pending14_manifest.read_text(encoding="utf-8")) if pending14_manifest.is_file() else {}
    pending14_inputs = pending14_validation.get("sourceInputsSha256", {})
    pending14_matches = bool(pending14_inputs) and all(
        Path(name).is_file() and source_digest(Path(name)) == digest for name, digest in pending14_inputs.items()
    ) and all(path.as_posix() in pending14_inputs for path in Path("app/src").rglob("*") if path.is_file())
    pending14_matches = pending14_matches and pending14_validation.get("buildPassed") is True and \
        pending14_validation.get("testsPassed") is True and pending14_validation.get("lintPassed") is True
    if pending14_matches:
        for item in items:
            if item["key"] in PENDING14_SOURCE_ROWS:
                item["previousStatus"] = item["status"]
                item["status"] = "partially_implemented_static_verified"
                item["buildManifest"] = pending14_manifest.as_posix()
                item["runtimeAccepted"] = False
    revision = subprocess.check_output(["git", "-C", str(args.reference), "rev-parse", "HEAD"], text=True).strip()
    payload = {
        "reference": "https://github.com/ReChronoRain/HyperCeiler",
        "revision": revision,
        "scope": "All system_ui*.xml keys plus old-control-center and notification-importance hooks configured in system_settings.xml; includes navigation/dependency rows, not all are hooks. Settings importance adapter has static APK evidence, not device acceptance.",
        "verification": f"Per-entry evidence only. Historical cumulative {test_count}-test/R8/full-lint validation: {validation.name}. Latest bounded remaining-14 continuation: {pending14_manifest.name}, matching-input gate={pending14_matches}. Status promotion requires matching source inputs; all original exclusions remain. Neither local validation nor historical installation is device acceptance.",
        "allMigrationComplete": False,
        "cumulativeValidationMatchesCurrentInputs": validation_matches,
        "cumulativeBuildManifest": validation.as_posix(),
        "cumulativeInstallManifest": install_manifest,
        "pending14ValidationManifest": pending14_manifest.as_posix(),
        "pending14ValidationMatchesCurrentInputs": pending14_matches,
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
        "navigationHandleAudit": "docs/navigation-handle-2026-10-10.md",
        "lockscreenWallpaperAudit": "docs/lockscreen-wallpaper-2026-10-10.md",
        "stockTilesAudit": "docs/stock-tiles-2026-10-10.md",
        "scrollFiveGCodeOnlyAudit": "docs/scroll-five-g-code-only-2026-10-10.md",
        "remainingSourceAudit": "docs/systemui-source-audit-2026-10-11.json",
        "remainingRowAudit": REMAINING_ROW_AUDIT_MANIFEST,
        "remainingContinuationAudit": "docs/remaining-migration-2026-10-11.md",
        "pending14ContinuationAudit": "docs/pending14-continuation-validation-2026-10-11.md",
        "pending14Remaining6CarrierAudit": "docs/pending14-remaining6-carrier-audit-2026-10-11.json",
        "nativeCardContinuationAudit": "docs/native-card-continuation-2026-10-11.md",
        "continuousProgressAudit": "docs/migration-continuous-progress-2026-10-11.md",
        "dimTileIconAudit": "docs/dim-tile-icon-2026-10-10.md",
        "volumeLongPressAudit": "docs/volume-long-press-2026-10-10.md",
        "codeOnlyContinuationAudit": "docs/code-only-migration-2026-10-10.md",
        "classicQsCodeOnlyAudit": "docs/classic-qs-code-only-2026-10-10.md",
        "tileCornerVolumeThemeCodeOnlyAudit": "docs/tile-corner-volume-theme-code-only-2026-10-10.md",
        "tileColorVolumeFooterCodeOnlyAudit": "docs/tile-color-volume-footer-code-only-2026-10-10.md",
        "tileIconAutoCollapseCodeOnlyAudit": "docs/tile-icon-auto-collapse-code-only-2026-10-10.md",
        "statusNote": "implemented_static_verified applies only to the named local scope, not full upstream parity. repaired_static_pending_device means the recorded defect has a source repair and regression coverage but no live acceptance; see repairedFindings and docs/migration-repair-0.1.3.md. code_reviewed_partial_unbuilt and code_reviewed_repair_unbuilt explicitly mean source-only review without compiling or running tests, not previous build coverage. Unreviewed rows remain needs_audit_existing_partial or pending. "
            "audited_carrier_found_not_migrated records a verified current-host carrier "
            "that has no local adapter yet and is not an implementation; audited_no_current_carrier "
            "records an upstream hook whose carrier does not exist on this host. Both are "
            "explicitly not migrated. Second-pass dex-xref reads corrected three earlier conclusions: the "
            "\"volume\" slot literal still exists on this host (PhoneStatusBarPolicy.mSlotMute), the airplane "
            "producer is MiuiStatusBarSignalPolicy rather than MiuiPhoneStatusBarPolicy, and the shared block "
            "lists hide their members instead of marking a right-hand cluster.",
        "items": items,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(items)} upstream entries -> {args.output}")


if __name__ == "__main__":
    main()
