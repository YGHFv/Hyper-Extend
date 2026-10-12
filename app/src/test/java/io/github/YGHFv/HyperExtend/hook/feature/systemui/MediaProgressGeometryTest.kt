/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.MediaProgressSettings
import org.junit.Assert.*
import org.junit.Test

class MediaProgressGeometryTest {
    @Test fun fractionsClampAndAvoidIntegerOverflow() {
        assertNull(MediaProgressGeometry.fraction(0, 0, 0))
        assertNull(MediaProgressGeometry.fraction(1, 10, 2))
        assertEquals(0f, MediaProgressGeometry.fraction(-3, 0, 100))
        assertEquals(1f, MediaProgressGeometry.fraction(200, 0, 100))
        assertEquals(.5f, MediaProgressGeometry.fraction(0, Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(.5f, MediaProgressGeometry.fraction(15, 10, 20))
    }
    @Test fun onlyActiveNonScrubbingPlaybackAnimates() {
        for (flags in 0..15) {
            assertEquals(flags == 7, MediaProgressGeometry.animate(flags and 1 != 0,
                flags and 2 != 0, flags and 4 != 0, flags and 8 != 0))
        }
    }
    @Test fun phaseIsBoundedAndOptionsRejectUnknownValues() {
        assertEquals(0f, MediaProgressGeometry.phase(-1))
        assertEquals(0f, MediaProgressGeometry.phase(2500))
        assertTrue(MediaProgressGeometry.phase(Long.MAX_VALUE) in 0f..1f)
        assertEquals(0, MediaProgressSettings.mode("3"))
        assertEquals(0, MediaProgressSettings.mode("-1"))
        assertEquals(2, MediaProgressSettings.mode("2"))
    }
    @Test fun customBackgroundAlphaPreservesRgbAndNeverBecomesMoreOpaque() {
        assertEquals(0x20345678, MediaProgressGeometry.alpha(0x80345678.toInt(), .25f))
        assertEquals(0x80345678.toInt(), MediaProgressGeometry.alpha(0x80345678.toInt(), 2f))
        assertEquals(0x00345678, MediaProgressGeometry.alpha(0x80345678.toInt(), -1f))
    }
}
