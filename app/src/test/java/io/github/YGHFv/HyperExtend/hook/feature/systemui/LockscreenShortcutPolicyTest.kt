/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.ScopeFeatureGroup
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.entryGroup
import org.junit.Assert.*
import org.junit.Test

class LockscreenShortcutPolicyTest {
    @Test fun exactSystemUiAndPluginPairRequired() {
        assertTrue(LockscreenShortcutPolicy.supported(202602260L, 22446301L))
        for ((sysui, plugin) in listOf(null to 22446301L, 202602260L to null, 202602261L to 22446301L, 202602260L to 22446302L))
            assertFalse(LockscreenShortcutPolicy.supported(sysui, plugin))
    }
    @Test fun sidesAreIndependentAndUnknownSideKeepsNative() {
        for (left in listOf(false, true)) for (right in listOf(false, true)) {
            assertEquals(left, LockscreenShortcutPolicy.hide(true, left, right))
            assertEquals(right, LockscreenShortcutPolicy.hide(false, left, right))
            assertFalse(LockscreenShortcutPolicy.hide(null, left, right))
        }
    }
    @Test fun launchOnlyBlocksTheUnambiguousHiddenSide() {
        for (left in listOf(false, true)) for (right in listOf(false, true)) {
            assertEquals(left, LockscreenShortcutPolicy.blockLaunch(true, false, left, right))
            assertEquals(right, LockscreenShortcutPolicy.blockLaunch(false, true, left, right))
            assertFalse(LockscreenShortcutPolicy.blockLaunch(false, false, left, right))
            assertFalse(LockscreenShortcutPolicy.blockLaunch(true, true, left, right))
        }
    }
    @Test fun repeatedEnforcementDoesNotForgetNativeVisibility() {
        for (native in listOf(0, 4, 8)) {
            val state = ShortcutVisibilityState()
            assertEquals(8, state.hide(native))
            assertEquals(8, state.hide(8))
            assertEquals(native, state.restore(8))
            assertEquals(8, state.restore(8))
        }
    }
    @Test fun hostAnimationVisibilityBecomesLatestBaseline() {
        val state = ShortcutVisibilityState()
        state.hide(0)
        state.hide(4)
        assertEquals(4, state.restore(8))
    }
    @Test fun restorationDoesNotOverwriteNewHostWrite() {
        val state = ShortcutVisibilityState()
        state.hide(0)
        assertEquals(4, state.restore(4))
    }
    @Test fun rebindAndReattachStartWithFreshNativeState() {
        val state = ShortcutVisibilityState()
        state.hide(0); state.restore(8)
        state.hide(4)
        assertEquals(4, state.restore(8))
    }
    @Test fun entriesAreSeparateOptInSystemUiSwitchesWithHonestBoundaries() {
        for (id in LockscreenShortcutPolicy.features) {
            val feature = featureById(id)!!
            assertFalse(feature.defaultEnabled)
            assertFalse(feature.hasDetailPage)
            assertEquals(listOf("systemui"), feature.scopes)
            assertEquals(ScopeFeatureGroup.LOCK_SCREEN, feature.entryGroup)
        }
        assertTrue(featureById(LockscreenShortcutPolicy.LEFT)!!.requirement!!.contains("不包含手电筒替换"))
        assertTrue(featureById(LockscreenShortcutPolicy.RIGHT)!!.requirement!!.contains("包括用户更换后的应用"))
    }
}
