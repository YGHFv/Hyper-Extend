/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ClassicQsPolicy {
    fun columns(requested: Int?, native: Int, maximum: Int, width: Int, padding: Int,
                margin: Int, density: Float): Int? {
        if (native <= 0 || maximum <= 0 || width <= 0 || padding < 0 || margin < 0 ||
            !density.isFinite() || density <= 0f) return null
        val usable = width.toLong() - padding
        val minimumCell = kotlin.math.ceil(48.0 * density).toLong() + margin
        if (usable < minimumCell || minimumCell <= 0L) return null
        val desired = requested?.takeIf { it in 3..8 } ?: return minOf(native, maximum)
        return minOf(desired, maximum, (usable / minimumCell).coerceAtMost(8).toInt())
    }

    fun rowLimit(requested: Int?, nativeMax: Int, nativeMin: Int): Int? {
        if (nativeMax <= 0 || nativeMin < 0 || nativeMin > nativeMax) return null
        return requested?.takeIf { it in 1..5 }?.coerceAtLeast(nativeMin) ?: nativeMax
    }

    fun redistribute(previous: Int?, effective: Int, nativeMax: Int): Boolean =
        if (previous == null) effective != nativeMax else previous != effective
}
