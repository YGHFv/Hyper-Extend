/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object NavigationHandleSettings {
    const val FEATURE = "navigation_handle_custom"
    const val RADIUS = "$FEATURE.radius"
    const val LIGHT_BACKGROUND = "$FEATURE.light_background"
    const val DARK_BACKGROUND = "$FEATURE.dark_background"
    val radius = HyperSlider(RADIUS, "提示线半径", 0, 500, 185, step = 5, divisor = 100,
        decimalPlaces = 2, unit = " dp", summary = "线条厚度为半径的两倍；0 只隐藏绘制，不关闭手势")
    val config = listOf(
        radius,
        HyperColor(LIGHT_BACKGROUND, "浅色背景下颜色", allowAlpha = true,
            summary = "随系统深浅背景判定平滑混合；可调不透明度，留空保留系统颜色"),
        HyperColor(DARK_BACKGROUND, "深色背景下颜色", allowAlpha = true,
            summary = "随系统深浅背景判定平滑混合；可调不透明度，留空保留系统颜色"),
    )
    fun radiusDp(raw: String): Float = (raw.toIntOrNull()?.coerceIn(radius.min, radius.max) ?: radius.default) / 100f
}
