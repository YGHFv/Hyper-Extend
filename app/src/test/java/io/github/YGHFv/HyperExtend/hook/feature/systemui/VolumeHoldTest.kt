/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class VolumeHoldTest {
    private fun hold() = VolumeHold(1000L, 50f, 60f, 7, 10f)

    @Test fun thresholdIs300MsAndFiresOnce() {
        val hold = hold()
        assertFalse(hold.fire(1299)); assertTrue(hold.fire(1300)); assertFalse(hold.fire(1400))
        assertTrue(hold.fired)
    }
    @Test fun backwardsOrVeryLateCallbackCannotExpand() {
        assertFalse(hold().fire(999)); assertFalse(hold().fire(2001))
        assertTrue(hold().fire(2000))
    }
    @Test fun jitterInsideTouchSlopIsAllowed() {
        val hold = hold()
        hold.sample(1100, 56f, 68f, 7, 1)
        assertTrue(hold.fire(1300))
    }
    @Test fun diagonalMoveOutsideSlopCancels() {
        val hold = hold()
        hold.sample(1100, 58f, 68f, 7, 1)
        assertFalse(hold.fire(1300)); assertTrue(hold.cancelled)
    }
    @Test fun movingBackNeverRearmsHold() {
        val hold = hold()
        hold.sample(1100, 80f, 60f, 7, 1)
        hold.sample(1200, 50f, 60f, 7, 1)
        assertFalse(hold.fire(1300))
    }
    @Test fun horizontalAndVerticalDragsBothCancel() {
        for ((x, y) in listOf(61f to 60f, 50f to 71f, 39f to 60f, 50f to 49f)) {
            val hold = hold(); hold.sample(1100, x, y, 7, 1); assertTrue(hold.cancelled)
        }
    }
    @Test fun changedPointerAndMultiTouchCancel() {
        for ((pointer, count) in listOf(8 to 1, 7 to 2, 7 to 0)) {
            val hold = hold(); hold.sample(1100, 50f, 60f, pointer, count); assertFalse(hold.fire(1300))
        }
    }
    @Test fun nonFiniteCoordinateCancels() {
        for (x in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val hold = hold(); hold.sample(1100, x, 60f, 7, 1); assertTrue(hold.cancelled)
        }
    }
    @Test fun historyBeforeDownCancels() {
        val hold = hold(); hold.sample(999, 50f, 60f, 7, 1); assertFalse(hold.fire(1300))
    }
    @Test fun releaseCancelOrLifecycleInvalidationCannotFire() {
        val hold = hold(); hold.cancel(); assertFalse(hold.fire(1300))
    }
    @Test fun invalidInitialStateNeverArms() {
        for (hold in listOf(VolumeHold(-1, 0f, 0f, 0, 10f), VolumeHold(0, Float.NaN, 0f, 0, 10f),
            VolumeHold(0, 0f, 0f, -1, 10f), VolumeHold(0, 0f, 0f, 0, -1f), VolumeHold(0, 0f, 0f, 0, Float.NaN))) {
            assertTrue(hold.cancelled); assertFalse(hold.fire(300))
        }
    }
    @Test fun cancelAfterFiringRetainsDrainOwnership() {
        val hold = hold(); assertTrue(hold.fire(1300)); hold.cancel()
        assertTrue(hold.fired); assertFalse(hold.fire(1400))
    }
    @Test fun separateSlidersDoNotShareCancellationOrFiring() {
        val first = hold(); val second = hold(); first.cancel()
        assertTrue(second.fire(1300)); assertFalse(first.fire(1300))
    }
}
