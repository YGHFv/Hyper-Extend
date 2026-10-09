/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumePanelStyleTest {
    private fun style() = AppVolumePanelStyle(1080, 2400, 440, 0, 0, 480, 44, 44, 77,
        176, 500, 38, 860, 520, 176, 600, List(6) { AppVolumeSpring(.9, .3) })

    @Test fun portraitUsesNativeTopInsteadOfCenteringShortCard() {
        val style = style()
        assertTrue(style.valid())
        assertEquals(480, style.localTop(0, 2400, 588))
        assertNotEquals((2400 - 588) / 2, style.localTop(0, 2400, 588))
    }

    @Test fun statusBarWindowOriginIsSubtractedExactlyOnce() {
        assertEquals(408, style().localTop(72, 2256, 588))
        assertEquals(480, 72 + style().localTop(72, 2256, 588))
    }

    @Test fun rightInsetAlreadyIncludedBySystemUiIsNotAddedAgain() {
        val style = style().copy(right = 120)
        assertEquals(120, style.localRight(0, 1080, 450))
        assertEquals(48, style.localRight(0, 1008, 450))
        assertEquals(120, style.localRight(72, 1008, 450))
    }

    @Test fun nativeLandscapeAnchorRemainsIndependentOfAppCount() {
        val style = style().copy(screenWidth = 2400, screenHeight = 1080, top = 180)
        assertEquals(180, style.localTop(0, 1080, 588))
        assertEquals(180, style.localTop(0, 1080, 630))
    }

    @Test fun smallWindowClampsBothEdges() {
        assertEquals(40, style().localTop(0, 600, 560))
        assertEquals(0, style().localTop(0, 300, 560))
        assertEquals(0, style().localRight(0, 400, 450))
    }

    @Test fun rotationDensityAndDisplayChangesInvalidateSnapshot() {
        val style = style()
        assertTrue(style.matches(1080, 2400, 440, 0, 0))
        assertFalse(style.matches(2400, 1080, 440, 1, 0))
        assertFalse(style.matches(1080, 2400, 440, 2, 0))
        assertFalse(style.matches(1080, 2400, 420, 0, 0))
        assertFalse(style.matches(1080, 2400, 440, 0, 1))
    }

    @Test fun nativeSliderDimensionsReplaceScreenPercentages() {
        val card = AppVolumeCardGeometry.calculate(1080, 2400, 2.75f, 2, style())
        assertEquals(176, card.sliderWidth)
        assertEquals(500, card.sliderHeight)
        assertEquals(19, card.halfGap)
        assertEquals(44, card.padding)
        assertEquals(478, card.cardWidth)
    }

    @Test fun nativeTabletFiveColumnPageStillFitsSmallWindow() {
        val card = AppVolumeCardGeometry.calculate(400, 300, 2.75f, 5, style())
        assertEquals(5, card.columns)
        assertTrue(card.cardWidth + 2 * card.endMargin <= 400)
        assertTrue(card.sliderHeight + 4 * card.padding <= 300)
    }

    @Test fun malformedSnapshotsAndNonFiniteSpringsAreRejected() {
        assertFalse(style().copy(top = -1).valid())
        assertFalse(style().copy(screenHeight = Int.MAX_VALUE).valid())
        assertFalse(style().copy(sourceWidth = 1080).valid())
        assertFalse(style().copy(springs = emptyList()).valid())
        assertFalse(style().copy(springs = List(6) { AppVolumeSpring(Double.NaN, .3) }).valid())
        assertFalse(AppVolumeSpring(.9, Double.POSITIVE_INFINITY).valid())
        assertFalse(AppVolumeSpring(0.0, .3).valid())
    }

    @Test fun hostSpringStartsAtSourceAndSettlesAtDestination() {
        for (spring in listOf(AppVolumeSpring(.82, .4), AppVolumeSpring(.9, .3), AppVolumeSpring(1.0, .3))) {
            assertEquals(0f, spring.progress(0.0), 0f)
            assertTrue(spring.progress(.05) in 0f..1f)
            assertEquals(1f, spring.progress(2.0), .0001f)
            assertTrue(spring.durationMillis in 200..1000)
        }
    }

    @Test fun layeredResponseChangesSpeedWithoutChangingTrajectory() {
        val fast = AppVolumeSpring(.82, .4)
        val slow = AppVolumeSpring(.82, .8)
        assertEquals(fast.progress(.1), slow.progress(.2), .00001f)
        assertTrue(kotlin.math.abs(fast.durationMillis * 2 - slow.durationMillis) <= 1)
    }
}
