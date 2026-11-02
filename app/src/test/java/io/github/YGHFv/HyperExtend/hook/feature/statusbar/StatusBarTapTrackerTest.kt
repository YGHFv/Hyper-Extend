/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class StatusBarTapTrackerTest {
    private fun tracker() = StatusBarTapTracker(50f, 8f, 300)
    private fun first(t: StatusBarTapTracker) {
        assertNull(t.down(10, 10, 5f, 5f))
        assertNull(t.up(30, 10, 5f, 5f))
    }

    @Test fun singleWaitsUntilDoubleWindowEndsAndRunsOnce() {
        val t = tracker()
        first(t)
        assertEquals(330L, t.deadline)
        assertNull(t.expire(329))
        assertEquals(StatusBarTapTracker.Trigger(false, 10, 10), t.expire(330))
        assertNull(t.expire(1000))
    }

    @Test fun doubleRunsOnlyOnSecondUpWithoutSingle() {
        val t = tracker()
        first(t)
        assertNull(t.down(100, 100, 5f, 5f))
        assertNull(t.expire(330))
        assertEquals(StatusBarTapTracker.Trigger(true, 10, 100), t.up(350, 100, 5f, 5f))
        assertNull(t.expire(1000))
        assertNull(t.up(351, 100, 5f, 5f))
    }

    @Test fun tooFastDoubleStillUsesOneClassifier() {
        val t = tracker()
        first(t)
        assertNull(t.down(40, 40, 5f, 5f))
        assertEquals(StatusBarTapTracker.Trigger(true, 10, 40), t.up(50, 40, 5f, 5f))
    }

    @Test fun secondOutsideDoubleRegionConfirmsPreviousSingle() {
        val t = tracker()
        first(t)
        assertEquals(StatusBarTapTracker.Trigger(false, 10, 10), t.down(100, 100, 80f, 5f))
        assertNull(t.up(120, 100, 80f, 5f))
        assertEquals(StatusBarTapTracker.Trigger(false, 100, 100), t.expire(420))
    }

    @Test fun lateSecondDownDoesNotCombineOldSingleEvenIfTimerWasDelayed() {
        val t = tracker()
        first(t)
        assertEquals(StatusBarTapTracker.Trigger(false, 10, 10), t.down(900, 900, 5f, 5f))
        assertNull(t.up(920, 900, 5f, 5f))
        assertEquals(StatusBarTapTracker.Trigger(false, 900, 900), t.expire(1220))
    }

    @Test fun historicalDragCancelsBothPendingActions() {
        val t = tracker()
        first(t)
        t.down(100, 100, 5f, 5f)
        t.move(30f, 5f)
        t.move(5f, 5f)
        assertNull(t.up(120, 100, 5f, 5f))
        assertNull(t.expire(1000))
    }

    @Test fun longPressNeverTurnsIntoSingleOrDouble() {
        val t = tracker()
        first(t)
        t.down(100, 100, 5f, 5f)
        assertNull(t.expire(400))
        assertNull(t.up(500, 100, 5f, 5f))
        assertNull(t.expire(1000))
    }

    @Test fun cancelDetachConfigurationAndSettingsChangeResetPending() {
        val t = tracker()
        first(t)
        t.reset()
        assertNull(t.expire(1000))
        t.down(1100, 1100, 5f, 5f)
        assertNull(t.up(1120, 1100, 5f, 5f))
    }

    @Test fun corruptSamplesAndDownTimeMismatchCannotExecute() {
        for ((time, down, x) in listOf(Triple(-1L, -1L, 5f), Triple(10L, 20L, 5f),
            Triple(10L, 10L, Float.NaN))) {
            val t = tracker()
            assertNull(t.down(time, down, x, 5f))
            assertNull(t.up(30, down, 5f, 5f))
            assertNull(t.expire(1000))
        }
        val t = tracker()
        first(t)
        assertNull(t.down(20, 20, 5f, 5f))
        assertNull(t.up(40, 20, 5f, 5f))
        assertNull(t.expire(1000))
        t.down(100, 100, 5f, 5f)
        assertNull(t.up(120, 101, 5f, 5f))
    }

    @Test fun thirdAndFourthTapFormNewPairNotRepeatedFirstDouble() {
        val t = tracker()
        first(t)
        t.down(100, 100, 5f, 5f)
        assertNotNull(t.up(120, 100, 5f, 5f))
        assertNull(t.down(160, 160, 5f, 5f))
        assertNull(t.up(180, 160, 5f, 5f))
        assertNull(t.down(220, 220, 5f, 5f))
        assertEquals(StatusBarTapTracker.Trigger(true, 160, 220), t.up(240, 220, 5f, 5f))
    }
}
