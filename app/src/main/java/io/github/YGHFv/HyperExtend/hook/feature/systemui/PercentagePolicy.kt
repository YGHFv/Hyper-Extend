/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.roundToInt

internal object PercentagePolicy {
    fun panelVolume(level: Int, min: Int, max: Int, muted: Boolean, sliderMax: Int, progress: Int): String? {
        // Super-volume adds an extra range to the slider. Keep its native indication,
        // rather than incorrectly calling the extended range "100%".
        if (min < 0 || max <= 0 || min > max || level !in 0..max ||
            max.toLong() * 1000 != sliderMax.toLong() || progress !in 0..sliderMax) return null
        return SliderValuePolicy.volume(level, max, muted)
    }
    data class Placement(val left: Int, val top: Int)
    fun place(width: Int, height: Int, labelWidth: Int, labelHeight: Int, leftInset: Int, topInset: Int,
        rightInset: Int, bottomInset: Int, topDp: Int, density: Float, landscape: Boolean): Placement? {
        if (width <= 0 || height <= 0 || labelWidth <= 0 || labelHeight <= 0 ||
            listOf(leftInset, topInset, rightInset, bottomInset).any { it < 0 } ||
            !density.isFinite() || density <= 0 || topDp !in 0..1024) return null
        val availableWidth = width.toLong() - leftInset - rightInset
        val availableHeight = height.toLong() - topInset - bottomInset
        if (labelWidth > availableWidth || labelHeight > availableHeight) return null
        val desired = topDp * density * if (landscape) 0.7f else 1f
        if (!desired.isFinite()) return null
        return Placement(leftInset + ((availableWidth - labelWidth) / 2).toInt(),
            desired.roundToInt().coerceIn(topInset, height - bottomInset - labelHeight))
    }
    fun active(now: Long, started: Long, enabled: Boolean, shown: Boolean, attached: Boolean,
        mainDisplay: Boolean, blocked: Boolean, keyguard: Boolean, touchExploration: Boolean) =
        enabled && shown && attached && mainDisplay && !blocked && !keyguard && !touchExploration &&
            now >= started && now - started < 30_000
}
