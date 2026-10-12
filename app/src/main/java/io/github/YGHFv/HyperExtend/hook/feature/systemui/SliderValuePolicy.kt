/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.PercentageSettings

/**
 * 「原位数值」分支的靶子与算术。
 *
 * 两个开关现在是 [PercentageSettings] 名下的子项（`slider_percentage.brightness` /
 * `slider_percentage.volume`），不再是两张独立卡片 —— 它们和浮层是同一个功能在同一位点
 * 的两种显示方式，拆成两张卡片只会让用户以为自己开了两件事。
 * 这里保留独立的对象只是为了让靶子/换算有唯一的归属，[features] 不再是「功能目录里的 id」，
 * 而是 `PercentageSettings` 的子项键。
 */
internal object SliderValuePolicy {
    const val BRIGHTNESS = PercentageSettings.BRIGHTNESS
    const val VOLUME = PercentageSettings.VOLUME
    val features = listOf(BRIGHTNESS, VOLUME)

    fun supported(systemUi: Long?, plugin: Long?) = systemUi == 202602260L && plugin == 183022200L

    // This is the slider's normalized range, not linear screen luminance or perceived loudness.
    fun brightness(value: Int, min: Int, max: Int): String? {
        if (min < 0 || max <= min || value !in min..max) return null
        return "${(value.toLong() - min) * 100 / (max.toLong() - min)}%"
    }

    fun volume(level: Int, max: Int, muted: Boolean): String? {
        if (max <= 0 || level !in 0..max) return null
        return if (muted) "0%" else "${level.toLong() * 100 / max}%"
    }
}
