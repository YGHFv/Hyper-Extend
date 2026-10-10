/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/** Visual sections are semantic, never inferred from the row's switch/arrow control. */
enum class FeaturePanel(val title: String, vararg val featureIds: String) {
    NOTIFICATIONS("通知", "notification_importance", "notification_remove_count_limit",
        NotificationExpansionSettings.EXPAND, NotificationExpansionSettings.COLLAPSE,
        "notification_disable_auto_fold", "notification_channel_settings", "notification_zen_fix",
        "notification_mute_when_interactive", "notification_disable_transparent", "notification_freeform"),
    FOCUS("焦点通知", "notification_unlock_focus"),
    MEDIA("媒体卡片", "media_card_progress", "media_card_always_dark", MediaBackgroundSettings.FEATURE,
        MediaCardSettings.LAYOUT, MediaCardSettings.TEXT_SIZE, "media_unlock_custom_actions"),
    NEW_CONTROL_CENTER("新控制中心", "control_center_hide_edit", "control_center_hide_carrier", "control_center_auto_collapse", "control_center_brightness_value", "control_center_volume_value"),
    CLASSIC_CONTROL_CENTER("经典控制中心", "control_center_unlock_old", ClassicQsSettings.FEATURE),
    TILES("磁贴", "control_center_fix_tiles_list", "control_center_dim_tile_icon", TileCornerSettings.FEATURE, TileColorSettings.FEATURE),
    VOLUME("音量面板", "volume_default_theme", "volume_hide_collapsed_footer", "volume_long_press_expand", AppVolumeSettings.FEATURE),
    ICONS("图标", "status_bar_icons", "native_notify_icon", "status_bar_screenshot_hide"),
    BATTERY("电池", "status_bar_battery_style"),
    NETWORK("网络", "status_bar_mobile", MobileTypeDisplaySettings.FEATURE, DualRowSignalSettings.FEATURE,
        MobileTypeTextSettings.FEATURE, "status_bar_network_speed"),
    CLOCK("时钟", ClockSettings.FEATURE),
    GESTURES("手势", "status_bar_double_tap", "lockscreen_double_tap"),
    LOCK_NOTIFICATIONS("锁屏通知", "lockscreen_show_notifications", "lockscreen_keep_notifications"),
    LOCK_APPEARANCE("锁屏显示", "lockscreen_hide_hint", "lockscreen_hide_zen", "lockscreen_hide_status_bar", "lockscreen_hide_ble_toast", LockscreenWallpaperSettings.FEATURE),
    LOCK_SHORTCUTS("底部快捷入口", "lockscreen_hide_left_shortcut", "lockscreen_left_flashlight", "lockscreen_hide_right_shortcut"),
    CHARGING("充电信息", LockscreenChargingSettings.FEATURE),
    LOCK_SECURITY("解锁与安全", "lockscreen_third_party_biometrics", "lockscreen_scramble_pin"),
    NAVIGATION("导航与方向", "gesture_line", NavigationHandleSettings.FEATURE, "rotation_suggestion", "rotation_lock_fix"),
    THEME("主题与取色", "wallpaper_monet", "systemui_monet_custom"),
    CLIPBOARD("剪贴板", "clipboard_native_overlay", "screenshot_clipboard", "milink_clipboard_guard"),
    TRANSFER("文件接收", "mishare_receive_guard"),
    CARD("卡面", "nfc_card_face"),
    CREDENTIALS("凭据与通行密钥", "passkey_fix"),
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
