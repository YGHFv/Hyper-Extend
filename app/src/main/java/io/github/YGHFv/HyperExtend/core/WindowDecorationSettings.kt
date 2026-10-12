/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object WindowDecorationSettings {
    const val HIDE_DOT = "window_hide_quick_button"
    const val DISABLE_MENU = "window_disable_quick_menu"
    const val DISABLE_INFINITY = "window_disable_infinity_gesture"
    const val HIDE_BOTTOM = "window_hide_bottom_bar"
}

internal val WINDOW_DECORATION_FEATURES = listOf(
    Triple(WindowDecorationSettings.HIDE_DOT, "隐藏快捷窗口按钮", "仅隐藏窗口顶部圆点，保留原触摸区域"),
    Triple(WindowDecorationSettings.DISABLE_MENU, "禁用快捷窗口菜单", "阻止顶部圆点新建快捷窗口菜单"),
    Triple(WindowDecorationSettings.DISABLE_INFINITY, "禁用无极窗口手势", "关闭无极手势入口，保留其他窗口操作"),
    Triple(WindowDecorationSettings.HIDE_BOTTOM, "隐藏小窗底部提示条", "隐藏自由小窗提示条并取消其预留高度"),
).map { (id, title, summary) ->
    HyperFeature(id = id, title = title, summary = summary, scopes = listOf("systemui"),
        origin = "西米露 / HyperCeiler · WMShell", license = "AGPL-3.0", defaultEnabled = false,
        group = ScopeFeatureGroup.SYSTEM_UI_OTHER,
        requirement = "仅适配已取证的系统窗口共享库；隐藏圆点不等于禁用点击。关闭后需原生重绘或重建窗口，完整恢复请重启系统界面。")
}
