/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object ClassicQsSettings {
    const val FEATURE = "classic_qs_layout"
    val rowsPortrait = HyperSlider("$FEATURE.rows_portrait", "展开面板最大行数（竖屏）", 2, 5, 3,
        summary = "最大行数，不强撑高度；仍受系统最小行数、可用高度和磁贴数量限制")
    val rowsLandscape = HyperSlider("$FEATURE.rows_landscape", "展开面板最大行数（横屏）", 1, 3, 2,
        summary = "最大行数，不修改每行列数或磁贴大小")
    val quickPortrait = HyperSlider("$FEATURE.quick_portrait", "折叠面板磁贴数量（竖屏）", 3, 7, 5,
        summary = "按已保存顺序取前几项；空间不足时系统可能显示更少")
    val quickLandscape = HyperSlider("$FEATURE.quick_landscape", "折叠面板磁贴数量（横屏）", 4, 8, 6,
        summary = "不压缩触摸区域，不修改用户磁贴顺序或经典控制中心开关")
    val config = listOf(rowsPortrait, rowsLandscape, quickPortrait, quickLandscape)

    fun value(raw: String, slider: HyperSlider): Int = raw.toIntOrNull()?.coerceIn(slider.min, slider.max) ?: slider.default
    fun row(orientation: Int, quick: Boolean): HyperSlider? = when (orientation) {
        1 -> if (quick) quickPortrait else rowsPortrait
        2 -> if (quick) quickLandscape else rowsLandscape
        else -> null
    }
}
