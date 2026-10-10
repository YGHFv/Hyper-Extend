/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object LockscreenWallpaperSettings {
    const val FEATURE = "lockscreen_wallpaper_transition"
    const val WAKE = "$FEATURE.wake"
    const val SLEEP = "$FEATURE.sleep"
    val wake = HyperSlider(WAKE, "亮屏壁纸动画时长", 100, 1600, 300, unit = " ms",
        summary = "数值越大越慢；只调整普通锁屏壁纸由暗变亮，不改变时钟和解锁动画")
    val sleep = HyperSlider(SLEEP, "息屏壁纸动画时长", 100, 1600, 200, unit = " ms",
        summary = "数值越大越慢；只调整普通锁屏壁纸变暗，不改变息屏显示开关")
    val config = listOf(wake, sleep)
    fun duration(show: Boolean, raw: String): Long {
        val row = if (show) wake else sleep
        return (raw.toIntOrNull()?.coerceIn(row.min, row.max) ?: row.default).toLong()
    }
}
