/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NavigationHandlePolicyTest {
    @Test fun mainThreadIsRequired() {
        assertTrue(NavigationHandlePolicy.supported(true))
        assertFalse(NavigationHandlePolicy.supported(false))
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

    @Test fun dimensionsRetainNativeInputsUnlessExplicitlyRequested() {
        assertEquals(NavigationHandlePolicy.Dimensions(null, null),
            NavigationHandlePolicy.dimensions(null, null, 0f, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f))
        val result = NavigationHandlePolicy.dimensions(100f, 8f, 2f, 500, 80, 4f, 0f, 10f, 4f, 1f, 1f)!!
        assertEquals(200f, result.width!!, 0f)
        assertEquals(16f, result.bottom!!, 0f)
    }

    @Test fun pressAndPulseGeometryCannotOverflowViewBounds() {
        val result = NavigationHandlePolicy.dimensions(400f, 0f, 3f, 300, 40, 4f, 1f, 20f, 8f, 1.5f, 2f)!!
        assertEquals(180f, result.width!!, 0f)
        assertEquals(12f, result.bottom!!, 0f)
        assertEquals(300f, (result.width!! + 20f) * 1.5f, 0f)
        val centerY = 40f - result.bottom!! - 4f
        val halfHeight = (8f + 8f) * 2f / 2
        assertTrue(centerY - halfHeight >= 0f)
        assertTrue(centerY + halfHeight <= 40f)
    }

    @Test fun invalidAnimationOrUnavailableLayoutFallsBackToNative() {
        assertNull(NavigationHandlePolicy.dimensions(100f, 0f, 2f, 0, 40, 4f, 0f, 0f, 0f, 1f, 1f))
        assertNull(NavigationHandlePolicy.dimensions(100f, 0f, 2f, 100, 4, 4f, 1f, 20f, 8f, 1f, 2f))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            assertNull(NavigationHandlePolicy.dimensions(100f, 0f, 2f, 100, 40, 4f, 0f, 0f, 0f, bad, 1f))
        }
    }
}
