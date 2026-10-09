/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

internal object AppVolumeExpandedLayout {
    // One of the host's animated columns is always the real STREAM_MUSIC column.
    fun appCapacity(appCount: Int, nativeColumns: Int): Int =
        minOf(appCount.coerceAtLeast(0), (nativeColumns - 1).coerceIn(0, 4))

    // Keep the animation's column arrays stable without blank sliders on the last page.
    fun pageStart(page: Int, appCount: Int, capacity: Int): Int =
        (page.toLong().coerceAtLeast(0) * capacity.coerceAtLeast(0))
            .coerceAtMost((appCount - capacity).coerceAtLeast(0).toLong()).toInt()

    fun contentWidth(columnWidths: List<Int>, gap: Int, trailing: Int): Int {
        require(columnWidths.isNotEmpty() && columnWidths.all { it > 0 } && gap >= 0 && trailing >= 0)
        return Math.toIntExact(columnWidths.sumOf { it.toLong() } + gap.toLong() * (columnWidths.size - 1) + trailing)
    }
}
