/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object NavigationGeometrySettings {
    const val FEATURE = "navigation_geometry"
    val barPortrait = HyperSlider("$FEATURE.height", "竖屏导航栏高度", 0, 800, 0, group = "导航栏",
        step = 5, divisor = 10, decimalPlaces = 1, unit = " dp", summary = "0 跟随系统；非零至少 4dp，仅手势导航")
    val barLandscape = barPortrait.copy(key = "$FEATURE.height_land", title = "横屏导航栏高度")
    val framePortrait = barPortrait.copy(key = "$FEATURE.frame", title = "竖屏导航栏框架高度", max = 1200,
        summary = "0 跟随系统；框架不会小于导航栏高度，不修改手势开关")
    val frameLandscape = framePortrait.copy(key = "$FEATURE.frame_land", title = "横屏导航栏框架高度")
    val config = listOf(barPortrait, barLandscape, framePortrait, frameLandscape)
}
