/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.ScopeFeatureGroup
import io.github.YGHFv.HyperExtend.core.LockscreenShortcutSettings
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
        // features 现在只有两项：左侧按钮（合并后含处理方式）与右侧按钮（仍是纯开关）。
        assertEquals(listOf(LockscreenShortcutSettings.FEATURE, LockscreenShortcutPolicy.RIGHT),
            LockscreenShortcutPolicy.features)
        for (id in LockscreenShortcutPolicy.features) {
            val feature = featureById(id)!!
            assertFalse(feature.defaultEnabled)
            assertEquals(id == LockscreenShortcutSettings.FEATURE, feature.hasDetailPage)
            assertEquals(listOf("systemui"), feature.scopes)
            assertEquals(ScopeFeatureGroup.LOCK_SCREEN, feature.entryGroup)
        }
        // 合并前两张卡片（隐藏 / 手电筒）不再各自存在，只剩带处理方式的左侧按钮卡片。
        assertNull(featureById(LockscreenShortcutSettings.LEGACY_FLASHLIGHT_CARD))
        assertNull(featureById("lockscreen_left_flashlight"))
        assertTrue(featureById(LockscreenShortcutPolicy.LEFT)!!.requirement!!.contains("按住至少 0.4 秒"))
        assertTrue(featureById(LockscreenShortcutPolicy.RIGHT)!!.requirement!!.contains("包括用户更换后的应用"))
    }
}
