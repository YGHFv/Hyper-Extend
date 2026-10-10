/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NavigationHandlePolicyTest {
    @Test fun exactVersionMainThreadAndHidePriorityAreRequired() {
        assertTrue(NavigationHandlePolicy.supported(202602260L, true, false))
        assertFalse(NavigationHandlePolicy.supported(null, true, false))
        assertFalse(NavigationHandlePolicy.supported(202602261L, true, false))
        assertFalse(NavigationHandlePolicy.supported(202602260L, false, false))
        assertFalse(NavigationHandlePolicy.supported(202602260L, true, true))
    }

    @Test fun dimensionsFollowCurrentDensityNotFontScale() {
        assertEquals(3.7f, NavigationHandlePolicy.radiusPixels(1.85f, 2f)!!, 0f)
        assertEquals(5.55f, NavigationHandlePolicy.radiusPixels(1.85f, 3f)!!, 0.0001f)
        assertEquals(0f, NavigationHandlePolicy.radiusPixels(0f, 3f)!!, 0f)
    }

    @Test fun invalidGeometryNeverReachesHost() {
        for (bad in listOf(-1f, 5.01f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(NavigationHandlePolicy.radiusPixels(bad, 2f))
        }
        for (bad in listOf(-1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
            assertNull(NavigationHandlePolicy.radiusPixels(5f, bad))
        }
    }

    @Test fun scopedStateIsVisibleOnlyDuringOriginalCall() {
        var radius = 2f
        var calls = 0
        val result = withNavigationHandleOverride({ radius = 5f }, { radius = 2f }) { calls++; radius * 2f }
        assertEquals(10f, result, 0f)
        assertEquals(2f, radius, 0f)
        assertEquals(1, calls)
    }

    @Test fun hostFailureRestoresWithoutReplay() {
        var radius = 2f
        var calls = 0
        val failure = IllegalStateException("host")
        try {
            withNavigationHandleOverride({ radius = 5f }, { radius = 2f }) { calls++; throw failure }
            fail("expected host failure")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(2f, radius, 0f)
        assertEquals(1, calls)
    }

    @Test fun partialPreparationFailureAlsoRestores() {
        var radius = 2f
        var called = false
        runCatching {
            withNavigationHandleOverride({ radius = 5f; error("field write") }, { radius = 2f }) { called = true }
        }
        assertEquals(2f, radius, 0f)
        assertFalse(called)
    }

    @Test fun nestedQueriesRestoreOuterValuesBeforeNativeValues() {
        var radius = 2f
        val original = radius
        withNavigationHandleOverride({ radius = 5f }, { radius = original }) {
            val outer = radius
            withNavigationHandleOverride({ radius = 4f }, { radius = outer }) { assertEquals(4f, radius, 0f) }
            assertEquals(5f, radius, 0f)
        }
        assertEquals(2f, radius, 0f)
    }
}
