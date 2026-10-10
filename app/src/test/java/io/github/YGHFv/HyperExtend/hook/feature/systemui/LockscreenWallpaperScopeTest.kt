/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class LockscreenWallpaperScopeTest {
    @Test fun onlyTheExactNativeEaseMatchesAndOnlyOnce() {
        val scope = LockscreenWallpaperScope()
        val ease = Any()
        assertNull(scope.take(ease))
        scope.withFrame(LockscreenWallpaperScope.Frame(ease, 321L)) {
            assertNull(scope.take(Any()))
            assertNull(scope.take(null))
            assertEquals(321L, scope.take(ease))
            assertNull(scope.take(ease))
        }
        assertNull(scope.take(ease))
    }

    @Test fun equalEaseStylesAreNotTheSameInstance() {
        data class Ease(val style: Int)
        val scope = LockscreenWallpaperScope()
        val original = Ease(20)
        scope.withFrame(LockscreenWallpaperScope.Frame(original, 100L)) {
            assertNull(scope.take(Ease(20)))
            assertEquals(100L, scope.take(original))
        }
    }

    @Test fun nestedExcludedCallsMaskOuterScopeAndRestoreIt() {
        val scope = LockscreenWallpaperScope()
        val ease = Any()
        scope.withFrame(LockscreenWallpaperScope.Frame(ease, 200L)) {
            scope.withFrame(null) { assertNull(scope.take(ease)) }
            assertEquals(200L, scope.take(ease))
        }
    }

    @Test fun nestedEligibleCallsHaveIndependentDurations() {
        val scope = LockscreenWallpaperScope()
        val ease = Any()
        scope.withFrame(LockscreenWallpaperScope.Frame(ease, 300L)) {
            scope.withFrame(LockscreenWallpaperScope.Frame(ease, 1600L)) { assertEquals(1600L, scope.take(ease)) }
            assertEquals(300L, scope.take(ease))
        }
    }

    @Test fun exceptionsClearScopeAndDoNotReplayNativeCalls() {
        val scope = LockscreenWallpaperScope()
        val ease = Any()
        var calls = 0
        val failure = IllegalStateException("host")
        val caught = runCatching {
            scope.withFrame(LockscreenWallpaperScope.Frame(ease, 400L)) { calls++; throw failure }
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
        assertNull(scope.take(ease))
    }

    @Test fun scopesAreNotInheritedByOtherThreadsOrLateCallbacks() {
        val scope = LockscreenWallpaperScope()
        val ease = Any()
        val result = AtomicReference<Long?>(-1L)
        scope.withFrame(LockscreenWallpaperScope.Frame(ease, 500L)) {
            Thread { result.set(scope.take(ease)) }.apply { start(); join() }
            assertNull(result.get())
            assertEquals(500L, scope.take(ease))
        }
        assertNull(scope.take(ease))
    }
}
