/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/** Visual sections are semantic, never inferred from the row's switch/arrow control. */
enum class FeaturePanel(val title: String, vararg val featureIds: String) {
    NOTIFICATIONS("通知", "notification_importance", "notification_remove_count_limit",
        NotificationExpansionSettings.EXPAND, NotificationExpansionSettings.COLLAPSE,
        "notification_disable_auto_fold", "notification_zen_fix",
        "notification_mute_when_interactive", "notification_disable_transparent", "notification_freeform", NotificationManagementSettings.FEATURE),
    FOCUS("焦点通知", "notification_unlock_focus", NotificationBlendSettings.FEATURE, "status_bar_hide_strong_toast"),
    ISLAND_MEDIA("灵动岛媒体", IslandMediaSettings.LAYOUT, IslandMediaSettings.TEXT, IslandMediaSettings.PROGRESS, IslandMediaSettings.BACKGROUND),
    MEDIA("媒体卡片", "media_card_progress", "media_card_always_dark", MediaBackgroundSettings.FEATURE,
        MediaCardSettings.LAYOUT, MediaCardSettings.TEXT_SIZE, "media_unlock_custom_actions"),
    NEW_CONTROL_CENTER("新控制中心", ForceBlurSettings.FEATURE, "shade_header_gradient_blur", HeaderWeatherSettings.FEATURE, PercentageSettings.FEATURE, "control_center_hide_edit", "control_center_hide_carrier", "control_center_hide_security_footer", "control_center_auto_collapse"),
    CLASSIC_CONTROL_CENTER("经典控制中心", "control_center_unlock_old", ClassicQsSettings.FEATURE),
    TILES("磁贴", "control_center_fix_tiles_list", "control_center_gms_tile", "control_center_taplus_tile", "control_center_sunlight_tile", AudioMuteSettings.FEATURE, QsLabelSettings.FEATURE, FlashlightStrengthSettings.FEATURE, "control_center_dim_tile_icon", TileMaterialSettings.FEATURE, TileCornerSettings.FEATURE, TileColorSettings.FEATURE, CardTileColorSettings.FEATURE, CardTileListSettings.FEATURE),
    VOLUME("音量面板", NativeSystemUiSettings.VOLUME, "volume_default_theme", "volume_hide_collapsed_footer", "volume_long_press_expand", AppVolumeSettings.FEATURE),
    POWER("电源菜单", NativeSystemUiSettings.POWER),
    WINDOWS("窗口", WindowDecorationSettings.HIDE_DOT, WindowDecorationSettings.DISABLE_MENU,
        WindowDecorationSettings.DISABLE_INFINITY, WindowDecorationSettings.HIDE_BOTTOM),
    ICONS("图标", "status_bar_icons", "status_bar_signal_order", "native_notify_icon", "status_bar_screenshot_hide"),
    BATTERY("电池", "status_bar_battery_style", BatteryDetailSettings.FEATURE),
    NETWORK("网络", "status_bar_mobile", MobileTypeDisplaySettings.FEATURE, DualRowSignalSettings.FEATURE,
        MobileTypeTextSettings.FEATURE, "status_bar_network_speed"),
    CLOCK("时钟", ClockSettings.FEATURE),
    GESTURES("手势", "status_bar_double_tap", "lockscreen_double_tap"),
    LOCK_NOTIFICATIONS("锁屏通知", "lockscreen_show_notifications", "lockscreen_keep_notifications"),
    LOCK_APPEARANCE("锁屏显示", "lockscreen_hide_hint", "lockscreen_hide_zen", "lockscreen_hide_status_bar", "lockscreen_hide_ble_toast", LockscreenWallpaperSettings.FEATURE),
    LOCK_SHORTCUTS("底部快捷入口", LockscreenShortcutSettings.FEATURE, "lockscreen_hide_right_shortcut", LockscreenMaterialSettings.FEATURE),
    CHARGING("充电信息", LockscreenChargingSettings.FEATURE),
    LOCK_SECURITY("解锁与安全", "lockscreen_third_party_biometrics", "lockscreen_scramble_pin"),
    NAVIGATION("导航与方向", NavigationHandleSettings.FEATURE, "rotation_suggestion"),
    FRAMEWORK_CORE("核心破解", "system_framework_core_patch_allow_update_system_app",
        "system_framework_core_patch_bypass_isolation_violation",
        "system_framework_core_patch_disable_low_api_check",
        "system_framework_core_patch_disable_persistent",
        "system_framework_core_patch_downgrade",
        "system_framework_core_patch_exact_signature",
        "system_framework_core_patch_verification_agent",
        "system_framework_core_patch_signature", "system_framework_core_patch_digest",
        "system_framework_core_patch_shared_user", "system_framework_use_android_package_installer",
        "system_framework_core_patch_fingerprint_guard"),
    FRAMEWORK_FREEFORM("小窗", "system_framework_freeform_count", "system_framework_freeform_blacklist",
        "system_framework_freeform_bubble", "system_framework_freeform_foreground_pin"),
    FRAMEWORK_VOLUME("系统音量", "system_framework_volume_default_stream",
        "system_framework_volume_first_press", "system_framework_volume_steps",
        "system_framework_volume_media_steps", "system_framework_volume_disable_safe"),
    FRAMEWORK_DISPLAY("系统显示", "system_framework_background_blur",
        "system_framework_enhance_recents_visibility", "system_framework_use_aosp_screenshot",
        "system_framework_all_dark_mode", "system_framework_hide_display_cutout",
        "system_framework_thermal_brightness", "system_framework_theme_provider"),
    FRAMEWORK_DISPLAY_NOTIFY("显示与通知", "system_framework_screen_all_rotations",
        "system_framework_quick_screenshot", "system_framework_anti_ques",
        "system_framework_disable_72h_verify", "system_framework_app_link_verify",
        "system_framework_untrusted_touch", "system_framework_delete_alert_window_notification",
        "system_framework_no_access_device_logs",
        "system_framework_disable_link_turbo_toast", "system_framework_notification_channel_blockable",
        "system_framework_flag_secure", "rotation_lock_fix"),
    FRAMEWORK_UNDERLYING("底层", "system_framework_disable_miui_watermark",
        "system_framework_speed_install", "system_framework_posted_clipboard",
        "system_framework_disable_cleaner", "system_framework_bypass_force_mi_app_store",
        "system_framework_market_use_detailmini", "system_framework_disable_miui_lite",
        "system_framework_native_file_picker",
        "system_framework_clipboard_whitelist", "system_framework_allow_disable_protected_package",
        "system_framework_bypass_unknown_sources", "system_framework_bypass_force_downloadui",
        "system_framework_bypass_wake_path_checker", "system_framework_disable_gesture_monitor",
        "system_framework_disable_thermal", "system_framework_gms_doze",
        "system_framework_hook_entry"),
    FRAMEWORK_SHORTCUTS("快捷菜单清理", "system_framework_clean_share_menu",
        "system_framework_clean_open_menu", "system_framework_clean_process_text_menu"),
    FRAMEWORK_LOCK_TASK("引导式访问", "system_framework_guided_access"),
    FRAMEWORK_INPUT("输入法", "system_framework_miui_ime_unlock", "system_framework_aosp_ime"),
    FRAMEWORK_MIPAD("平板输入", "system_framework_mipad_stylus_gesture",
        "system_framework_mipad_stylus_bluetooth", "system_framework_mipad_restore_esc",
        "system_framework_mipad_gesture_fingers"),
    FRAMEWORK_AUDIO("音效", "system_framework_misound_effect"),
    SECURITY_CENTER_SECURITY("安全与安装", "security_center_disable_root_environment",
        "security_center_install_intercept", "security_center_adb_install_verify",
        "security_center_disable_safepay_auto_scan", "security_center_disable_report"),
    PACKAGE_INSTALLER_SECURITY("安装安全", "package_installer_disable_cloud_check",
        "package_installer_disable_risk_check", "package_installer_disable_ads",
        "package_installer_disable_app_upload", "package_installer_disable_count_check",
        "package_installer_disable_full_safe_version", "package_installer_disable_safe_mode_tip",
        "package_installer_all_system_app", "package_installer_install_source",
        "package_installer_display_more_info"),
    GUARD_PROVIDER_SECURITY("安全检测", "guard_provider_disable_root_check",
        "guard_provider_disable_app_list_upload"),
    CLOUD_CONTROL("云控", "joyose_disable_cloud_control", "joyose_enable_gpu_tuner"),
    TRUST_SECURITY("风险检测", "trustservice_disable_mrm"),
    CLOUD_SYNC("云同步", "micloud_service_list"),
    TELEMETRY("遥测上报", "analytics_fuck_guard"),
    HTML_VIEWER_CLOUD("云控", "html_viewer_disable_cloud_control"),
    MARKET_SECURITY("应用安全", "market_bypass_risk_check",
        "market_disable_package_monitor", "market_disable_start_push_dialog",
        "market_disable_ads", "market_device_modify"),
    THEME("主题与取色", "wallpaper_monet", "systemui_monet_custom"),
    CLIPBOARD("剪贴板", "clipboard_native_overlay", "screenshot_clipboard", "milink_clipboard_guard"),
    TRANSFER("文件接收", "mishare_receive_guard"),
    TOAST("Toast", ToastMaterialSettings.FEATURE),
    CARD("卡面", "nfc_card_face"),
    CREDENTIALS("凭据与通行密钥", "passkey_fix"),
    LAUNCHER("桌面", "launcher_native_shortcuts_enabled", "home_other_disable_prestart"),
    OTHER("其他功能"),
}

private val panelByFeature = FeaturePanel.entries.flatMap { panel -> panel.featureIds.map { it to panel } }.toMap()
val HyperFeature.panel: FeaturePanel get() = panelByFeature[id] ?: FeaturePanel.OTHER

/** Scope and submenu remain part of the key so search cannot merge unrelated hosts. */
data class FeaturePanelKey(val scopeId: String?, val group: ScopeFeatureGroup?, val panel: FeaturePanel)
val HyperFeature.panelKey: FeaturePanelKey get() = FeaturePanelKey(entryScope?.id, entryGroup, panel)

fun <T> groupFeaturePanels(items: List<T>, feature: (T) -> HyperFeature): Map<FeaturePanelKey, List<T>> =
    items.groupBy { feature(it).panelKey }

fun featurePanels(features: List<HyperFeature>): Map<FeaturePanelKey, List<HyperFeature>> =
    groupFeaturePanels(features) { it }

data class FeatureDetailPanel(val title: String, val options: List<HyperOption>, val config: List<HyperConfigRow>)

/** Equal group names share one card even when some rows are switches and others are values. */
fun featureDetailPanels(feature: HyperFeature): List<FeatureDetailPanel> {
    val defaultTitle = "常规"
    val titles = (feature.options.map { it.group ?: defaultTitle } + feature.config.map { it.group ?: defaultTitle }).distinct()
    return titles.map { title -> FeatureDetailPanel(title,
        feature.options.filter { (it.group ?: defaultTitle) == title },
        feature.config.filter { (it.group ?: defaultTitle) == title }) }
}
