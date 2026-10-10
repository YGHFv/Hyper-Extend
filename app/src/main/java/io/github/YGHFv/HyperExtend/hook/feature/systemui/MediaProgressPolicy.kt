/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaProgressPolicy {
    fun currentHeight(idle: Int, pressedMaximum: Int, pressed: Boolean): Int = if (pressed) pressedMaximum else idle
    fun height(tenthsDp: Int, density: Float, originalMax: Int): Int? {
        if (!density.isFinite() || density <= 0f || originalMax < 2) return null
        val px = (tenthsDp.coerceIn(10, 160) * density / 10f).toInt().coerceIn(2, originalMax)
        return px - px % 2
    }
}
