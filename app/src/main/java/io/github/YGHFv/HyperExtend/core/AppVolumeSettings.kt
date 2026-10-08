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
}

val APP_VOLUME_FEATURE = HyperFeature(
    id = AppVolumeSettings.FEATURE,
    title = "音量条上方分应用音量入口",
    summary = "顶部入口与右侧分应用音量卡片（实验性）",
    scopes = listOf("systemui", "misound"),
    origin = "HyperVolumeANC · zhhhyyyyyy",
    license = "Apache-2.0",
    defaultEnabled = false,
    group = ScopeFeatureGroup.CONTROL_CENTER,
    requirement = "同时勾选系统界面和音质音效作用域，修改后重启系统界面及音质音效。已静态核验音质音效 260903、音量插件 183022200，尚未真机验收。包含顶部入口、隐藏原生悬浮球、右侧局部毛玻璃卡片及滑入滑出动画，保留原生音量调节逻辑；不含降噪。不支持的音质音效版本保留原生界面。锁屏、无媒体播放、展开二级菜单、翻折外屏或顶部空间不足时不显示。不要与 HyperVolumeANC、AppVolumeBarHook、Soundman 同类入口同时开启。",
)
