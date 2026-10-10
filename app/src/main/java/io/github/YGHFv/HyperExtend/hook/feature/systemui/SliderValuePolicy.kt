/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object SliderValuePolicy {
    const val BRIGHTNESS = "control_center_brightness_value"
    const val VOLUME = "control_center_volume_value"
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
