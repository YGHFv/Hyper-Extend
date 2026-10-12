/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class MergedCardMigrationTest {
    @Test fun untouchedLegacyKeysLeaveEverythingAlone() {
        assertNull(MergedCardMigration.sliderValuePlan(null, null, null))
        assertNull(MergedCardMigration.sliderValuePlan(null, null, "2"))
    }

    @Test fun bothLegacyCardsOffOnlyRemovesKeysWithoutDisablingAnything() {
        // 老键都为假：不能把 false 搬到新子项上 —— 用户在 slider_percentage.brightness
        // 那边可能本来就开着，搬 false 会静默关掉它。
        for (style in listOf(null, "1", "2")) {
            val plan = MergedCardMigration.sliderValuePlan(false, false, style)!!
            assertTrue(plan.switches.isEmpty())
            assertTrue(plan.configs.isEmpty())
        }
    }

    @Test fun brightnessCardMigratesToTheInlineBrightnessOption() {
        val plan = MergedCardMigration.sliderValuePlan(true, null, null)!!
        assertEquals(mapOf(PercentageSettings.BRIGHTNESS to true, PercentageSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(PercentageSettings.STYLE to "1"), plan.configs)
    }

    @Test fun volumeCardMigratesToTheInlineVolumeOption() {
        val plan = MergedCardMigration.sliderValuePlan(null, true, null)!!
        assertEquals(mapOf(PercentageSettings.VOLUME to true, PercentageSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(PercentageSettings.STYLE to "1"), plan.configs)
    }

    @Test fun bothCardsMigrateTogether() {
        val plan = MergedCardMigration.sliderValuePlan(true, true, null)!!
        assertEquals(3, plan.switches.size)
        assertTrue(plan.switches.getValue(PercentageSettings.BRIGHTNESS))
        assertTrue(plan.switches.getValue(PercentageSettings.VOLUME))
        assertTrue(plan.switches.getValue(PercentageSettings.FEATURE))
        assertEquals(mapOf(PercentageSettings.STYLE to "1"), plan.configs)
    }

    @Test fun explicitStyleIsNeverOverwritten() {
        // 用户已经显式选过浮层或原位，合并时只补子项，不动他的显示方式。
        for (style in listOf("1", "2")) {
            val plan = MergedCardMigration.sliderValuePlan(true, true, style)!!
            assertTrue(style, plan.configs.isEmpty())
            assertEquals(3, plan.switches.size)
        }
    }

    @Test fun oneCardOnOneCardOffStillIgnoresTheOffSide() {
        val plan = MergedCardMigration.sliderValuePlan(true, false, null)!!
        assertEquals(mapOf(PercentageSettings.BRIGHTNESS to true, PercentageSettings.FEATURE to true), plan.switches)
        assertFalse(plan.switches.containsKey(PercentageSettings.VOLUME))
    }

    @Test fun navigationMergeIsNoOpWithoutEitherLegacyCard() {
        assertNull(MergedCardMigration.navigationMergePlan(null, null, null))
        assertNull(MergedCardMigration.navigationMergePlan(null, null, "1"))
    }

    @Test fun disabledLegacyNavigationCardsNeverTurnTheMergedCardOff() {
        for (mode in listOf(null, "1", "2", "3")) {
            val plan = MergedCardMigration.navigationMergePlan(false, false, mode)!!
            assertTrue(plan.switches.isEmpty())
            assertTrue(plan.configs.isEmpty())
        }
    }

    @Test fun hideCardMigratesToHideMode() {
        val plan = MergedCardMigration.navigationMergePlan(true, null, null)!!
        assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(NavigationHandleSettings.MODE to NavigationHandleSettings.MODE_HIDE), plan.configs)
    }

    @Test fun geometryCardMigratesToGeometryMode() {
        val plan = MergedCardMigration.navigationMergePlan(null, true, null)!!
        assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(NavigationHandleSettings.MODE to NavigationHandleSettings.MODE_GEOMETRY), plan.configs)
    }

    @Test fun bothLegacyCardsOnPreferGeometry() {
        val plan = MergedCardMigration.navigationMergePlan(true, true, null)!!
        assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(NavigationHandleSettings.MODE to NavigationHandleSettings.MODE_GEOMETRY), plan.configs)
    }

    @Test fun explicitProcessingModeIsNeverOverwritten() {
        for (mode in listOf(NavigationHandleSettings.MODE_APPEARANCE, NavigationHandleSettings.MODE_HIDE,
            NavigationHandleSettings.MODE_GEOMETRY)) {
            val plan = MergedCardMigration.navigationMergePlan(true, true, mode)!!
            assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), plan.switches)
            assertTrue(mode, plan.configs.isEmpty())
        }
    }

    @Test fun notificationMenuMergeIsNoOpWithoutTheLegacyCard() {
        assertNull(MergedCardMigration.notificationMenuPlan(null, null))
        assertNull(MergedCardMigration.notificationMenuPlan(null, "2"))
    }

    @Test fun disabledChannelCardNeverChangesTheMenuTarget() {
        for (target in listOf(null, "1", "2")) {
            val plan = MergedCardMigration.notificationMenuPlan(false, target)!!
            assertTrue(plan.switches.isEmpty())
            assertTrue(plan.configs.isEmpty())
        }
    }

    @Test fun enabledChannelCardMigratesToChannelTarget() {
        val plan = MergedCardMigration.notificationMenuPlan(true, null)!!
        assertTrue(plan.switches.isEmpty())
        assertEquals(mapOf(NotificationImportanceSettings.MENU_TARGET to NotificationImportanceSettings.TARGET_CHANNEL),
            plan.configs)
    }

    @Test fun explicitMenuTargetIsNeverOverwrittenByMigration() {
        for (target in listOf(NotificationImportanceSettings.TARGET_APP, NotificationImportanceSettings.TARGET_CHANNEL)) {
            val plan = MergedCardMigration.notificationMenuPlan(true, target)!!
            assertTrue(target, plan.configs.isEmpty())
        }
    }

    @Test fun notificationMenuDefaultFollowsTheNativeAppSettings() {
        val row = NotificationImportanceSettings.config.single()
        assertEquals(NotificationImportanceSettings.TARGET_APP, row.default)
        assertFalse(NotificationImportanceSettings.channelRedirect(""))
        assertFalse(NotificationImportanceSettings.channelRedirect(NotificationImportanceSettings.TARGET_APP))
        assertTrue(NotificationImportanceSettings.channelRedirect(NotificationImportanceSettings.TARGET_CHANNEL))
    }

    @Test fun leftButtonMergeIsNoOpWithoutEitherLegacyCard() {
        assertNull(MergedCardMigration.leftButtonMergePlan(null, null, null))
        assertNull(MergedCardMigration.leftButtonMergePlan(null, null, LockscreenShortcutSettings.MODE_HIDE))
    }

    @Test fun disabledLeftButtonCardsNeverEnableTheMergedCard() {
        for (mode in listOf(null, LockscreenShortcutSettings.MODE_DEFAULT, LockscreenShortcutSettings.MODE_HIDE)) {
            val plan = MergedCardMigration.leftButtonMergePlan(false, false, mode)!!
            assertTrue(plan.switches.isEmpty())
            assertTrue(plan.configs.isEmpty())
        }
    }

    @Test fun flashlightOnlyCardMigratesToTheFlashlightMode() {
        val plan = MergedCardMigration.leftButtonMergePlan(null, true, null)!!
        assertEquals(mapOf(LockscreenShortcutSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(LockscreenShortcutSettings.MODE to LockscreenShortcutSettings.MODE_FLASHLIGHT), plan.configs)
    }

    @Test fun hideOnlyCardMigratesToTheHideModeInsteadOfSilentlyRevertingToDefault() {
        // 老卡片的主键就是新卡片的主键，但 mode 为空等于「保持原生」——那不是用户的选择。
        val plan = MergedCardMigration.leftButtonMergePlan(true, null, null)!!
        assertEquals(mapOf(LockscreenShortcutSettings.FEATURE to true), plan.switches)
        assertEquals(mapOf(LockscreenShortcutSettings.MODE to LockscreenShortcutSettings.MODE_HIDE), plan.configs)
    }

    @Test fun bothLeftButtonCardsOnPreserveWhatTheUserActuallySaw() {
        // 合并前隐藏优先：两张都开着时用户看到的是「按钮不见了」，不是手电筒。
        val plan = MergedCardMigration.leftButtonMergePlan(true, true, null)!!
        assertEquals(mapOf(LockscreenShortcutSettings.MODE to LockscreenShortcutSettings.MODE_HIDE), plan.configs)
    }

    @Test fun explicitLeftButtonModeIsNeverOverwritten() {
        for (mode in listOf(LockscreenShortcutSettings.MODE_DEFAULT, LockscreenShortcutSettings.MODE_HIDE,
            LockscreenShortcutSettings.MODE_FLASHLIGHT)) {
            val plan = MergedCardMigration.leftButtonMergePlan(true, true, mode)!!
            assertEquals(mapOf(LockscreenShortcutSettings.FEATURE to true), plan.switches)
            assertTrue(mode, plan.configs.isEmpty())
        }
    }
}
