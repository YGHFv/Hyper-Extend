/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaButtonSizePolicy {
    fun size(main: Int, custom: Int, isMain: Boolean): Int? =
        (if (!isMain && custom != 140) custom else main).takeIf { it != 140 }?.coerceIn(50, 200)

    fun scale(size: Int, width: Int, height: Int, drawableWidth: Int, drawableHeight: Int): Float? {
        if (width <= 0 || height <= 0 || drawableWidth <= 0 || drawableHeight <= 0) return null
        return minOf(size, width, height).toFloat() / maxOf(drawableWidth, drawableHeight)
    }
}
