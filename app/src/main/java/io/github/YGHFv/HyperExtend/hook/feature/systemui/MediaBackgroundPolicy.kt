/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.roundToInt

internal object MediaBackgroundPolicy {
    const val SIZE = 192
    fun eligible(mode: Int, attached: Boolean, tiny: Boolean, blocked: Boolean, keyguard: Boolean?, showingKeyguard: Boolean?, aod: Boolean?, pendingAod: Boolean): Boolean =
        mode in 1..4 && attached && !tiny && !blocked && keyguard == false && showingKeyguard == false && aod == false && !pendingAod

    // Cap every output channel to 46: even opaque white artwork remains readable
    // with the native night secondary text (50% white) on top of this background.
    fun darken(color: Int): Int = 0xff000000.toInt() or
        (((color ushr 16 and 255) * .18f).roundToInt() shl 16) or
        (((color ushr 8 and 255) * .18f).roundToInt() shl 8) or
        ((color and 255) * .18f).roundToInt()

    fun radius(percent: Int, size: Int): Int = (percent.coerceIn(0, 20) * size / 100).coerceAtLeast(0)

    /** Two separable box passes; bounded work independent of blur radius. */
    fun blur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        require(width > 0 && height > 0 && source.size == width * height)
        val r = radius.coerceIn(0, maxOf(width, height))
        if (r == 0) return source.copyOf()
        var input = source.copyOf()
        repeat(2) {
            val horizontal = IntArray(input.size)
            val output = IntArray(input.size)
            fun pass(src: IntArray, dst: IntArray, length: Int, rows: Int, index: (Int, Int) -> Int) {
                val divisor = r * 2 + 1
                for (row in 0 until rows) {
                    var red = 0; var green = 0; var blue = 0
                    fun add(at: Int, sign: Int) {
                        val c = src[index(at.coerceIn(0, length - 1), row)]
                        red += (c ushr 16 and 255) * sign; green += (c ushr 8 and 255) * sign; blue += (c and 255) * sign
                    }
                    for (x in -r..r) add(x, 1)
                    for (x in 0 until length) {
                        dst[index(x, row)] = 0xff000000.toInt() or ((red / divisor) shl 16) or ((green / divisor) shl 8) or (blue / divisor)
                        add(x - r, -1); add(x + r + 1, 1)
                    }
                }
            }
            pass(input, horizontal, width, height) { x, y -> y * width + x }
            pass(horizontal, output, height, width) { y, x -> y * width + x }
            input = output
        }
        return input
    }
}

/** A result from a detached/rebound view can never publish into its new binding. */
internal class MediaArtworkGeneration {
    @Volatile // Mutated on main; read by the worker before processing/posting cancelled work.
    private var value = 0L
    fun next(): Long = ++value
    fun accepts(token: Long): Boolean = token == value
}
