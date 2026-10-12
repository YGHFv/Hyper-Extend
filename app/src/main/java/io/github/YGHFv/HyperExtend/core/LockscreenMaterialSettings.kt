/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object LockscreenMaterialSettings {
    const val FEATURE = "lockscreen_shortcut_blur"
    const val ADVANCED = "$FEATURE.advanced"
    const val COLOR = "$FEATURE.color"
    const val RADIUS = "$FEATURE.radius"
    val options = listOf(HyperOption(ADVANCED, "使用高级材质", defaultEnabled = false,
        summary = "仅在原生背景模糊已开启且当前宿主支持时使用；不强开系统材质设置"))
    val config = listOf(
        HyperColor(COLOR, "背景颜色", allowAlpha = true, summary = "留空使用低透明白色；较不透明时为图标选择可读颜色"),
        HyperSlider(RADIUS, "背景圆半径", 10, 60, 32, unit = " dp",
            summary = "当前宿主独立适配，不沿用上游像素 inset 映射；按按钮实际大小限制，不改点击区域"),
    )
    fun radius(raw: String) = raw.toIntOrNull()?.coerceIn(10, 60) ?: 32
    fun color(raw: String) = SystemUiCustomSettings.color(raw, true) ?: 0x3cffffff
}
