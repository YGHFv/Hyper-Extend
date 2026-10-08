/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class DoubleTapTrackerTest {
    private fun tap(tracker: DoubleTapTracker, at: Long, x: Float = 5f): Boolean {
        tracker.down(at, x, 5f)
        return tracker.up(at + 30, x, 5f)
    }

    @Test fun requiresTwoCompletedTapsAndResetsAfterSuccess() {
        val tracker = DoubleTapTracker(20f)
        assertFalse(tap(tracker, 10))
        assertTrue(tap(tracker, 100))
        assertFalse(tap(tracker, 180))
        assertTrue(tap(tracker, 260))
    }

    @Test fun rejectsDistantSlowOrLongPressTaps() {
        val tracker = DoubleTapTracker(20f)
        assertFalse(tap(tracker, 10))
        assertFalse(tap(tracker, 400))
        assertFalse(tap(tracker, 500, 100f))
        tracker.down(600, 100f, 5f)
        assertFalse(tracker.up(1000, 100f, 5f))
        assertFalse(tap(tracker, 1100, 100f))
    }

    @Test fun dragCancelAndUnlockInvalidateTheFirstTap() {
        val tracker = DoubleTapTracker(20f)
        assertFalse(tap(tracker, 10))
        tracker.down(100, 5f, 5f)
        tracker.move(50f, 5f)
        assertFalse(tracker.up(120, 5f, 5f))
        assertFalse(tap(tracker, 200))
        tracker.reset()
        assertFalse(tap(tracker, 250))
        assertFalse(tracker.up(260, 5f, 5f))
    }

    @Test fun rejectsNonMonotonicEvents() {
        val tracker = DoubleTapTracker(20f)
        tracker.down(100, 5f, 5f)
        assertFalse(tracker.up(90, 5f, 5f))
        assertFalse(tap(tracker, 50))
        assertFalse(tap(tracker, 20))
    }

    @Test fun aShortSwipeIsNotATapEvenWithinDoubleTapSlop() {
        val tracker = DoubleTapTracker(100f, dragSlop = 8f)
        assertFalse(tap(tracker, 10))
        tracker.down(100, 5f, 5f)
        tracker.move(20f, 5f)
        assertFalse(tracker.up(130, 20f, 5f))
        assertFalse(tap(tracker, 200))
    }
}
