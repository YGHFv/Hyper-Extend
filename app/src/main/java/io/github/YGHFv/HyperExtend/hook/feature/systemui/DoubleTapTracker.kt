/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.abs

/** Monotonic event times, completed taps only; drags/cancel/multi-touch invalidate the pair. */
internal class DoubleTapTracker(
    private val slop: Float,
    private val timeout: Long = 250L,
    private val dragSlop: Float = slop,
) {
    private var downAt = -1L
    private var downX = 0f
    private var downY = 0f
    private var lastUp = -1L
    private var lastX = 0f
    private var lastY = 0f

    fun reset() { downAt = -1; lastUp = -1 }

    fun down(time: Long, x: Float, y: Float) { downAt = time; downX = x; downY = y }

    fun move(x: Float, y: Float) {
        if (abs(x - downX) > dragSlop || abs(y - downY) > dragSlop) reset()
    }

    fun up(time: Long, x: Float, y: Float): Boolean {
        move(x, y)
        if (downAt < 0 || time - downAt !in 0..timeout) { reset(); return false }
        val doubled = lastUp >= 0 && downAt - lastUp in 0..timeout &&
            abs(x - lastX) <= slop && abs(y - lastY) <= slop
        downAt = -1
        lastUp = if (doubled) -1 else time
        lastX = x
        lastY = y
        return doubled
    }
}
