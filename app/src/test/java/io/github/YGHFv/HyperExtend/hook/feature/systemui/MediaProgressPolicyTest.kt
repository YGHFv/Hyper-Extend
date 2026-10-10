/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaProgressPolicyTest {
    @Test fun layoutRefreshKeepsTheNativePressedTrack() {
        assertEquals(48, MediaProgressPolicy.currentHeight(24, 48, true))
        assertEquals(24, MediaProgressPolicy.currentHeight(24, 48, false))
    }
    @Test fun convertsTenthsOfDpWithoutTreatingEightyAsEightyDp() {
        assertEquals(24, MediaProgressPolicy.height(80, 3f, 48))
        assertEquals(8, MediaProgressPolicy.height(80, 1f, 48))
        assertEquals(10, MediaProgressPolicy.height(55, 2f, 48))
    }

    @Test fun respectsNativePressedMaximumAndEvenPixelContract() {
        for (raw in -10..200) {
            val result = MediaProgressPolicy.height(raw, 3.5f, 35)!!
            assertTrue(result in 2..35)
            assertEquals(0, result % 2)
        }
    }

    @Test fun invalidDensityOrDimensionsLeaveNativeTrackUntouched() {
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) assertNull(MediaProgressPolicy.height(80, density, 48))
        assertNull(MediaProgressPolicy.height(80, 3f, 1))
    }
}
