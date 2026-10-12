/* Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal data class MediaPalette(val primary: Int, val secondary: Int, val start: Int, val end: Int) {
    fun blend(to: MediaPalette, alpha: Int) = MediaPalette(
        MediaPalettePolicy.blend(primary, to.primary, alpha), MediaPalettePolicy.blend(secondary, to.secondary, alpha),
        MediaPalettePolicy.blend(start, to.start, alpha), MediaPalettePolicy.blend(end, to.end, alpha))
}

internal inline fun <T> withMediaPaletteOverride(prepare: () -> Unit, restore: () -> Unit, original: () -> T): T =
    try { prepare(); original() } finally { restore() }

internal object MediaPalettePolicy {
    fun brightness(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var sum = 0L
        for (c in pixels) sum += (c ushr 16 and 255) * 299L + (c ushr 8 and 255) * 587L + (c and 255) * 114L
        return (sum / pixels.size / 1000).toInt()
    }

    fun colors(mode: Int, inverse: Boolean, brightness: Int, accent1: List<Int>, accent2: List<Int>,
        neutral1: List<Int>, neutral2: List<Int>): MediaPalette? {
        if (mode !in 1..4 || brightness !in 0..255 ||
            listOf(accent1, accent2, neutral1, neutral2).any { it.size != 13 || it.any { c -> c ushr 24 != 255 } }) return null
        return when {
            mode == 4 && inverse && brightness >= 192 -> MediaPalette(accent1[8], accent1[8], accent1[3], accent1[3])
            mode == 1 || mode == 4 -> MediaPalette(accent1[2], accent1[2], accent1[8], accent1[8])
            else -> MediaPalette(neutral1[1], neutral2[3], accent2[9], accent1[9])
        }
    }

    fun blend(from: Int, to: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        fun channel(shift: Int) = (((from ushr shift and 255) * (255 - a) + (to ushr shift and 255) * a + 127) / 255) shl shift
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    fun withAlpha(color: Int, alpha: Int) = (color and 0xffffff) or (alpha.coerceIn(0, 255) shl 24)

    // Bounded local sampling avoids MiPalette's process-global scratch array/native library.
    fun dominant(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val counts = IntArray(512)
        val red = LongArray(512); val green = LongArray(512); val blue = LongArray(512)
        for (color in pixels) {
            val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
            val index = (r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
            counts[index]++; red[index] += r; green[index] += g; blue[index] += b
        }
        val best = counts.indices.maxByOrNull { counts[it] } ?: return null
        val n = counts[best]
        return 0xff000000.toInt() or ((red[best] / n).toInt() shl 16) or
            ((green[best] / n).toInt() shl 8) or (blue[best] / n).toInt()
    }
}
