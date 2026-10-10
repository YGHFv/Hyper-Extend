/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

object AppVolumeSettings {
    const val FEATURE = "volume_app_entry"
    const val PACKAGE = "com.miui.misound"
    const val SERVICE = "$PACKAGE.playervolume.VolumeUIService"
    const val ACTION = "io.github.YGHFv.HyperExtend.action.OPEN_APP_VOLUME"
    const val SENDER_PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    const val POSITION = "volume_app_entry.position"
}

enum class AppVolumeButtonPosition(val variant: String, val label: String) {
    ABOVE_VOLUME("above_volume", "新增·音量条上方"),
    ABOVE_SILENT("above_silent", "新增·静音按钮上方"),
    ABOVE_DND("above_dnd", "新增·勿扰按钮上方"),
    BELOW_DND("below_dnd", "新增·勿扰按钮下方"),
    REPLACE_SILENT("replace_silent", "替换·静音按钮"),
    REPLACE_DND("replace_dnd", "替换·勿扰按钮"),
    ;

    val replacesNative: Boolean get() = this == REPLACE_SILENT || this == REPLACE_DND
    val inFooter: Boolean get() = this != ABOVE_VOLUME
    val targetsSilent: Boolean get() = this == ABOVE_SILENT || this == REPLACE_SILENT

    companion object {
        fun fromVariant(value: String?): AppVolumeButtonPosition = entries.firstOrNull { it.variant == value } ?: ABOVE_VOLUME
    }
}

val APP_VOLUME_FEATURE = HyperFeature(
    id = AppVolumeSettings.FEATURE,
    title = "统一多应用音量调节样式",
    summary = "统一按钮与官方展开面板内的多应用音量样式（实验性）",
    scopes = listOf("systemui", "misound"),
    origin = "HyperVolumeANC · zhhhyyyyyy",
    license = "Apache-2.0",
    defaultEnabled = false,
    group = ScopeFeatureGroup.CONTROL_CENTER,
    config = listOf(
        HyperChoice(
            key = AppVolumeSettings.POSITION,
            title = "多应用音量调节按钮位置",
            entries = AppVolumeButtonPosition.entries.map { ChoiceEntry(it.variant, it.label) },
            summary = "仅在有应用声音输出时显示；替换位置在按钮隐藏后恢复原按钮",
        ),
    ),
    requirement = "同时勾选系统界面和音质音效作用域，修改后重启两个宿主。仅适配音质音效 260903、音量插件 183022200，原顶部入口在当前适配设备已确认使用正常；新增位置已静态核验，横竖屏、多页及异常场景仍需完整回归。SystemUI 承载应用滑块并直接使用官方展开/关闭动画，音质音效只提供数据与应用音量写入。保留原生媒体总音量列，分应用面板内隐藏静音、勿扰和定时快捷按钮。媒体总音量静音时仍可调应用比例，不自动解除总静音；主动操作总音量列遵循系统行为。多页时点击应用图标或页码切页，末页回填前页应用以避免空槽；音源集合变化后关闭，重新打开刷新。暂不支持共享 UID、其他用户或双开资料的应用，不含降噪。适配版本且钩子完整时隐藏原生悬浮球，不必先打开模块面板。所有位置都仅在有应用声音输出时显示；锁屏、无媒体播放、展开二级菜单、翻折外屏或可用空间不足时不显示。新增和替换按钮均保留原生点击行为作为隐藏时的回退；选择静音/勿扰附近位置时依赖原生底部按钮区，若启用隐藏静音和勿扰按钮，请使用音量条上方位置。不要与 HyperVolumeANC、AppVolumeBarHook、Soundman 同类功能同时开启。",
)
