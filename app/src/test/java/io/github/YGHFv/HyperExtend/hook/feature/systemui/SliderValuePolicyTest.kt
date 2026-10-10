/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

// Added for a future authorized test run; not executed in the code-only migration batch.
class SliderValuePolicyTest {
    @Test fun onlyAuditedVersionsAreAccepted() {
        assertTrue(SliderValuePolicy.supported(202602260, 183022200))
        assertFalse(SliderValuePolicy.supported(null, 183022200))
        assertFalse(SliderValuePolicy.supported(202602260, 183022201))
    }
    @Test fun brightnessUsesActualRangeAndLongArithmetic() {
        assertEquals("0%", SliderValuePolicy.brightness(10, 10, 110))
        assertEquals("50%", SliderValuePolicy.brightness(60, 10, 110))
        assertEquals("100%", SliderValuePolicy.brightness(Int.MAX_VALUE, 0, Int.MAX_VALUE))
    }
    @Test fun invalidBrightnessRangesDoNotInventPercentages() {
        assertNull(SliderValuePolicy.brightness(0, 0, 0))
        assertNull(SliderValuePolicy.brightness(100, 0, 99))
        assertNull(SliderValuePolicy.brightness(-1, 0, 99))
        assertNull(SliderValuePolicy.brightness(1, -1, 99))
    }
    @Test fun volumeUsesNativeDiscreteLevelAndMute() {
        assertEquals("46%", SliderValuePolicy.volume(7, 15, false))
        assertEquals("0%", SliderValuePolicy.volume(7, 15, true))
        assertEquals("100%", SliderValuePolicy.volume(Int.MAX_VALUE, Int.MAX_VALUE, false))
    }
    @Test fun invalidVolumeIsNotTreatedAsMaximum() {
        assertNull(SliderValuePolicy.volume(0, 0, false))
        assertNull(SliderValuePolicy.volume(16, 15, false))
        assertNull(SliderValuePolicy.volume(-1, 15, true))
    }
}
