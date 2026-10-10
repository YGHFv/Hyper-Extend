/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaDarkPolicyTest {
    @Test fun onlyAuditedMainScreenAndMainThreadAreEligible() {
        assertTrue(MediaDarkPolicy.enabled(202602260, true, false, false))
        assertFalse(MediaDarkPolicy.enabled(202602261, true, false, false))
        assertFalse(MediaDarkPolicy.enabled(202602260, false, false, false))
        assertFalse(MediaDarkPolicy.enabled(202602260, true, true, false))
        assertFalse(MediaDarkPolicy.enabled(202602260, true, false, true))
    }

    @Test fun darkModeOnlyChangesNightBitsAndIsIdempotent() {
        for (mode in listOf(0, 0x11, 0x12, 0x23, 0x45, 0x1137)) {
            val dark = MediaDarkPolicy.nightMode(mode)
            assertEquals(0x20, dark and 0x30)
            assertEquals(mode and 0x30.inv(), dark and 0x30.inv())
            assertEquals(dark, MediaDarkPolicy.nightMode(dark))
        }
    }

    @Test fun contextIsScopedToTheNativeCall() {
        val native = Any(); val dark = Any(); var current = native
        val value = withMediaColorContext({ current }, { current = it }, dark) {
            assertSame(dark, current); 42
        }
        assertEquals(42, value)
        assertSame(native, current)
    }

    @Test fun nativeFailurePropagatesOnceAndRestoresContext() {
        val native = Any(); var current = native; val error = IllegalStateException("native"); var calls = 0
        val result = runCatching { withMediaColorContext({ current }, { current = it }, Any()) { calls++; throw error } }
        assertSame(error, result.exceptionOrNull())
        assertEquals(1, calls)
        assertSame(native, current)
    }

    @Test fun nestedCallsRestoreEachScope() {
        val native = Any(); val first = Any(); val second = Any(); var current = native
        withMediaColorContext({ current }, { current = it }, first) {
            withMediaColorContext({ current }, { current = it }, second) { assertSame(second, current) }
            assertSame(first, current)
        }
        assertSame(native, current)
    }

    @Test fun newerNativeContextIsNotClobbered() {
        val replacement = Any(); var current = Any()
        withMediaColorContext({ current }, { current = it }, Any()) { current = replacement }
        assertSame(replacement, current)
    }

    @Test fun failedTemporaryWriteRestoresIfItWasPublished() {
        val native = Any(); val dark = Any(); var current = native
        val result = runCatching { withMediaColorContext({ current }, { current = it; if (it === dark) error("write") }, dark) { fail() } }
        assertTrue(result.isFailure)
        assertSame(native, current)
    }

    @Test fun latestMaterialEffectSupersedesOlderRestoration() {
        val state = MediaEffectOwnership<String>(); val first = Any(); val next = Any()
        state.record(first, "blur")
        state.record(next, "glass")
        assertEquals("glass", state.take(next))
        assertNull(state.take(next))
    }

    @Test fun newerHostBackgroundIsNeverRestoredToOldMaterial() {
        val state = MediaEffectOwnership<String>(); val owned = Any()
        state.record(owned, "old")
        assertNull(state.take(Any()))
        assertNull(state.take(owned))
    }

    @Test fun ownershipUsesIdentityNotDrawableEquality() {
        data class Same(val id: Int)
        val state = MediaEffectOwnership<String>(); val owned = Same(1)
        state.record(owned, "old")
        assertNull(state.take(Same(1)))
    }

    @Test fun nullBackgroundIsStillAnOwnedNativeWrite() {
        val state = MediaEffectOwnership<String>()
        assertNull(state.take(null))
        state.record(null, "normal")
        assertEquals("normal", state.take(null))
    }

    @Test fun nativeNightUpdateDiscardsPreviousOverrideEvenWithSameDrawable() {
        val state = MediaEffectOwnership<String>(); val drawable = Any()
        state.record(drawable, "old day context")
        state.clear()
        assertNull(state.take(drawable))
    }

    @Test fun deferredAodRestorationMustRetainExactBackgroundOwnership() {
        val state = MediaEffectOwnership<String>(); val native = Any()
        state.record(native, "restore after pending AOD")
        assertNull(state.take(Any()))
        assertNull(state.take(native))
    }

    @Test fun reapplyingNativeMaterialReplacesOwnershipWhenItAllocatesANewDrawable() {
        val state = MediaEffectOwnership<String>(); val old = Any(); val reapplied = Any()
        state.record(old, "dark")
        state.record(reapplied, "dark")
        assertEquals("dark", state.take(reapplied))
        assertNull(state.take(old))
    }
}
