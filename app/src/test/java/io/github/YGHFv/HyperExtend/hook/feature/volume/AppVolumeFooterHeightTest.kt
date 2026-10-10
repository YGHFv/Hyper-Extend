/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumeFooterHeightTest {
    @Test fun addedRowExpandsFixedNativeFrameWithoutAccumulating() {
        val state = AppVolumeFooterHeight()
        var height = 100
        repeat(30) { height = state.apply(height, 44); assertEquals(144, height) }
        assertEquals(44, state.extra)
        assertEquals(100, state.restore(height))
        assertEquals(0, state.extra)
    }

    @Test fun nativeConfigurationChangeReplacesBaselineAndIsNotOverwritten() {
        val state = AppVolumeFooterHeight()
        assertEquals(144, state.apply(100, 44))
        assertEquals(180, state.apply(130, 50))
        assertEquals(130, state.restore(180))
        state.apply(100, 44)
        assertEquals(170, state.restore(170))
    }

    @Test fun nonFixedLayoutsRemainNative() {
        for (height in listOf(-1, -2, 0)) {
            val state = AppVolumeFooterHeight()
            assertEquals(height, state.apply(height, 44))
            assertEquals(0, state.extra)
        }
    }

    @Test fun pendingLayoutDoesNotSubtractHeightThatHasNotBeenMeasuredYet() {
        val state = AppVolumeFooterHeight()
        state.apply(100, 44)
        assertEquals(0, state.measuredExtra)
        state.onLayout(144)
        assertEquals(44, state.measuredExtra)
        state.restore(144)
        assertEquals(44, state.measuredExtra)
        state.onLayout(100)
        assertEquals(0, state.measuredExtra)
    }

    @Test fun nativeLayoutChangeDoesNotCountAnOldAdditionAsMeasured() {
        val state = AppVolumeFooterHeight()
        state.apply(100, 44)
        state.onLayout(144)
        assertEquals(44, state.measuredExtra)
        state.onLayout(130)
        assertEquals(0, state.measuredExtra)
        assertEquals(180, state.apply(130, 50))
        assertEquals(0, state.measuredExtra)
        state.onLayout(180)
        assertEquals(50, state.measuredExtra)
    }

    @Test fun clampedNativeFrameIsNotTreatedAsFullyExpanded() {
        val state = AppVolumeFooterHeight()
        state.apply(100, 44)
        state.onLayout(120)
        assertEquals(0, state.measuredExtra)
    }
}
