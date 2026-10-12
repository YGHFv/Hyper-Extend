/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenMaterialPolicy {
    fun diameter(radius: Int, density: Float, width: Int, height: Int): Int? {
        if (radius !in 10..60 || !density.isFinite() || density <= 0 || width <= 0 || height <= 0) return null
        val pixels = 2f * radius * density
        if (!pixels.isFinite()) return null
        return minOf(pixels.toInt(), width, height).takeIf { it > 0 }
    }
    fun foreground(color: Int): Int? {
        if (color ushr 24 <= 92) return null
        val brightness = (color ushr 16 and 255) * 299 + (color ushr 8 and 255) * 587 + (color and 255) * 114
        return if (brightness < 128000) -1 else 0xff000000.toInt()
    }
}
