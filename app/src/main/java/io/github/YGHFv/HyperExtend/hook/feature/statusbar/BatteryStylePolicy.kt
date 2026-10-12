/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object BatteryStylePolicy {
    data class Padding(val start: Int, val top: Int, val end: Int)
    fun padding(left: Int, right: Int, vertical: Int, markVertical: Int, density: Float,
        markPresent: Boolean, markVisible: Boolean, markHiddenByPolicy: Boolean,
        nativeMarkTop: Int = 0): Pair<Padding, Padding>? {
        if (!density.isFinite() || density <= 0f || density > 10f) return null
        fun px(value: Float) = (value * density).toInt()
        val start = px(left.coerceIn(0, 10) * 0.5f)
        val end = px(right.coerceIn(0, 10) * 0.5f)
        val top = px((vertical.coerceIn(0, 24) - 12) * 0.5f)
        val markOffset = markVertical.coerceIn(0, 27)
        val markTop = if (markOffset == 27) nativeMarkTop else px((markOffset - 8) * 0.5f)
        val trailingMark = markPresent && markVisible && !markHiddenByPolicy
        return Padding(start, top, if (trailingMark) 0 else end) to
            Padding(0, markTop, if (trailingMark) end else 0)
    }
}
