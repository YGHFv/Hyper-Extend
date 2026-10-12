/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ToastMaterialPolicy {
    fun eligible(enabled: Boolean, blocked: Boolean, attached: Boolean, shown: Boolean, mainDisplay: Boolean,
        interactive: Boolean, locked: Boolean, nativeBlur: Boolean, now: Long, started: Long) =
        enabled && !blocked && attached && shown && mainDisplay && interactive && !locked && !nativeBlur &&
            now >= started && now - started < 30_000
    fun tint(text: Int): Int {
        val brightness = (text ushr 16 and 255) * 299 + (text ushr 8 and 255) * 587 + (text and 255) * 114
        return if (brightness >= 128_000) 0xde202020.toInt() else 0xdefafafa.toInt()
    }
    fun radius(width: Int, height: Int, density: Float): Float? =
        if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0) null
        else minOf(24 * density, minOf(width, height) / 2f)
}
