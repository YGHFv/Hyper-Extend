/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumeCardGeometryTest {
    @Test fun oldDismissCannotCloseReattachedOrReopenedCard() {
        val transition = AppVolumeCardTransition()
        val old = transition.close()
        assertTrue(transition.accepts(old))
        transition.reset()
        assertFalse(transition.accepts(old))
        val next = transition.close()
        assertFalse(transition.accepts(old))
        assertTrue(transition.accepts(next))
    }

    @Test fun phoneMatchesReferenceCardProportions() {
        val card = AppVolumeCardGeometry.calculate(1080, 2400, 2.75f, 2)
        assertEquals(170, card.sliderWidth)
        assertEquals(530, card.sliderHeight)
        assertEquals(20, card.halfGap)
        assertEquals(44, card.endMargin)
        assertEquals(420, card.pagerWidth)
        assertEquals(468, card.cardWidth)
    }

    @Test fun landscapeKeepsPortraitSliderProportions() {
        val portrait = AppVolumeCardGeometry.calculate(1080, 2400, 2.75f, 3)
        assertEquals(portrait, AppVolumeCardGeometry.calculate(2400, 1080, 2.75f, 3))
    }

    @Test fun tabletNativeFiveColumnPagesAreNotCroppedToThree() {
        val card = AppVolumeCardGeometry.calculate(2560, 1600, 2f, 5)
        assertEquals(5, card.columns)
        assertEquals(5 * (card.sliderWidth + 2 * card.halfGap), card.pagerWidth)
        assertTrue(card.cardWidth + 2 * card.endMargin <= 2560)
    }

    @Test fun smallWindowScalesCardToFitRatherThanClippingSliders() {
        val card = AppVolumeCardGeometry.calculate(400, 240, 2f, 5)
        assertTrue(card.cardWidth + 2 * card.endMargin <= 400)
        assertTrue(card.sliderHeight + 4 * card.padding <= 240)
        assertTrue(card.sliderWidth > 0)
        assertTrue(card.sliderHeight > 0)
    }

    @Test fun transientEmptyListsAndInvalidBoundsStillHavePositiveSizes() {
        val card = AppVolumeCardGeometry.calculate(0, 0, 1f, 0)
        assertEquals(1, card.columns)
        assertTrue(card.sliderWidth > 0)
        assertTrue(card.sliderHeight > 0)
    }
}
