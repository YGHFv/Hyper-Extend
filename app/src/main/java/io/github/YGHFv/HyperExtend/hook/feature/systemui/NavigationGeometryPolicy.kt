/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.roundToInt

internal object NavigationGeometryPolicy {
    data class Geometry(val bar: Int, val frame: Int)
    fun landscape(rotation: Int): Boolean? = when (rotation) {
        0, 2 -> false
        1, 3 -> true
        else -> null
    }
    fun eligible(version: Long, display: Int?, mode: Int, forcedButtons: Boolean, main: Boolean) =
        version == 202602260L && display == 0 && mode == 2 && !forcedButtons && main

    fun resolve(bar: Int, frame: Int, density: Float, nativeBar: Int, nativeFrame: Int): Geometry? {
        if (!density.isFinite() || density <= 0 || nativeBar <= 0 || nativeFrame < nativeBar) return null
        if (bar == 0 && frame == 0) return null
        fun pixels(value: Int, limit: Int) = (value.coerceIn(40, limit) * density / 10).roundToInt().coerceAtLeast(1)
        val nextBar = if (bar <= 0) nativeBar else pixels(bar, 800)
        val nextFrame = if (frame <= 0) nativeFrame else pixels(frame, 1200)
        return Geometry(nextBar, maxOf(nextBar, nextFrame))
    }
}
