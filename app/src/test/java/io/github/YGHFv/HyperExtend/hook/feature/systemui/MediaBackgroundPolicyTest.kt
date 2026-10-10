/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaBackgroundPolicyTest {
    private fun allowed(mode: Int = 1, attached: Boolean = true, tiny: Boolean = false, blocked: Boolean = false,
        keyguard: Boolean? = false, showing: Boolean? = false, aod: Boolean? = false, pending: Boolean = false) =
        MediaBackgroundPolicy.eligible(mode, attached, tiny, blocked, keyguard, showing, aod, pending)

    @Test fun allCustomModesRequireNormalAttachedCard() {
        for (mode in 1..4) assertTrue(allowed(mode))
        for (mode in listOf(-1, 0, 5)) assertFalse(allowed(mode))
        assertFalse(allowed(attached = false)); assertFalse(allowed(tiny = true)); assertFalse(allowed(blocked = true))
    }
    @Test fun keyguardAndAodNeverRenderArtworkIncludingUnknownState() {
        assertFalse(allowed(keyguard = true)); assertFalse(allowed(showing = true)); assertFalse(allowed(aod = true))
        assertFalse(allowed(keyguard = null)); assertFalse(allowed(showing = null)); assertFalse(allowed(aod = null))
        assertFalse(allowed(pending = true))
    }
    @Test fun darkeningBoundsEveryColorChannelAndMakesPixelsOpaque() {
        for (channel in 0..255) {
            val c = MediaBackgroundPolicy.darken((channel shl 16) or (channel shl 8) or channel)
            assertEquals(255, c ushr 24)
            assertTrue(c and 255 <= 46)
            assertEquals(c and 255, c ushr 16 and 255)
        }
        assertEquals(0xff000000.toInt(), MediaBackgroundPolicy.darken(0))
    }
    @Test fun worstCaseNativeHalfWhiteSecondaryTextRemainsReadable() {
        fun luminance(channel: Double): Double {
            val value = channel / 255
            return if (value <= .04045) value / 12.92 else Math.pow((value + .055) / 1.055, 2.4)
        }
        val background = 46.0
        val secondary = 255 * (128.0 / 255) + background * (127.0 / 255)
        assertTrue((luminance(secondary) + .05) / (luminance(background) + .05) >= 4.5)
    }
    @Test fun blurZeroCopiesWithoutMutatingInput() {
        val input = intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt())
        val output = MediaBackgroundPolicy.blur(input, 2, 1, 0)
        assertArrayEquals(input, output); assertNotSame(input, output)
    }
    @Test fun uniformImageAndOnePixelRemainStableAtMaximumRadius() {
        val color = 0xff4182c3.toInt()
        assertArrayEquals(IntArray(6) { color }, MediaBackgroundPolicy.blur(IntArray(6) { color }, 2, 3, 100))
        assertArrayEquals(intArrayOf(color), MediaBackgroundPolicy.blur(intArrayOf(color), 1, 1, 20))
    }
    @Test fun blurKeepsRedChannelsSeparateAndClampsEdges() {
        val input = intArrayOf(0xffff0000.toInt(), 0xff000000.toInt(), 0xff000000.toInt())
        val out = MediaBackgroundPolicy.blur(input, 3, 1, 1)
        assertTrue((out[0] ushr 16 and 255) > (out[2] ushr 16 and 255))
        assertTrue(out.all { it and 0xffff == 0 && it ushr 24 == 255 })
        assertEquals(0xffff0000.toInt(), input[0])
    }
    @Test fun nonSquareBlurIsDeterministic() {
        val input = IntArray(35) { 0xff000000.toInt() or it * 0x030201 }
        assertArrayEquals(MediaBackgroundPolicy.blur(input, 7, 5, 2), MediaBackgroundPolicy.blur(input, 7, 5, 2))
    }
    @Test fun blurStrengthHasRealZeroAndBoundedMaximum() {
        assertEquals(0, MediaBackgroundPolicy.radius(-1, 192))
        assertEquals(0, MediaBackgroundPolicy.radius(0, 192))
        assertEquals(38, MediaBackgroundPolicy.radius(99, 192))
    }
    @Test fun staleArtworkTokensFailAfterDetachOrRebind() {
        val generation = MediaArtworkGeneration()
        val old = generation.next(); assertTrue(generation.accepts(old))
        val current = generation.next(); assertFalse(generation.accepts(old)); assertTrue(generation.accepts(current))
        generation.next(); assertFalse(generation.accepts(current))
    }
    @Test fun invalidDimensionsDoNotSilentlyReturnGarbage() {
        assertTrue(runCatching { MediaBackgroundPolicy.blur(IntArray(2), 2, 2, 1) }.isFailure)
        assertTrue(runCatching { MediaBackgroundPolicy.blur(IntArray(0), 0, 1, 1) }.isFailure)
    }
}
