/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SystemUiFeaturesTest {
    @Test fun autoFoldSwitchAlsoDocumentsMenuRemovalAndRecovery() {
        val feature = featureById("notification_disable_auto_fold")!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.summary.contains("收纳到更多通知"))
        assertTrue(feature.requirement!!.contains("仍可手动移出"))
    }

    @Test fun newFeaturesAreOptInAndRemainInSystemUi() {
        assertEquals(38, SYSTEM_UI_FEATURES.size)
        SYSTEM_UI_FEATURES.forEach { feature ->
            assertFalse(feature.defaultEnabled)
            assertEquals(if (feature.id in setOf("control_center_unlock_old", "notification_importance")) listOf("systemui", "settings")
                else listOf("systemui"), feature.scopes)
            assertEquals(feature.config.isNotEmpty() || feature.options.isNotEmpty() || feature.extra != null, feature.hasDetailPage)
            assertNotNull(feature.entryGroup)
            assertTrue(feature.requirement!!.contains("重启系统界面"))
            assertTrue(feature.origin.contains("HyperCeiler"))
        }
    }

    @Test fun eachNewSubmenuContainsOnlyItsOwnFeatures() {
        for (group in listOf(ScopeFeatureGroup.LOCK_SCREEN, ScopeFeatureGroup.CONTROL_CENTER, ScopeFeatureGroup.SYSTEM_UI_OTHER)) {
            assertEquals(
                ((SYSTEM_UI_FEATURES + APP_VOLUME_FEATURE).filter { it.group == group }.map { it.id } +
                    if (group == ScopeFeatureGroup.SYSTEM_UI_OTHER) listOf("gesture_line", "wallpaper_monet", "rotation_suggestion") else emptyList()).toSet(),
                featuresOfScopePage("systemui", group).map { it.id }.toSet(),
            )
        }
        val allKeys = FEATURES.flatMap { listOf(it.id) + it.options.map { option -> option.id } + it.config.map { row -> row.key } }
        assertEquals(allKeys.size, allKeys.toSet().size)
    }

    @Test fun lockscreenPrivacyImplicationsRemainVisible() {
        assertTrue(featureById("lockscreen_show_notifications")!!.requirement!!.contains("隐私"))
        assertTrue(featureById("lockscreen_keep_notifications")!!.requirement!!.contains("隐私"))
        assertTrue(featureById("lockscreen_hide_ble_toast")!!.requirement!!.contains("不改变蓝牙解锁条件"))
        assertTrue(featureById("lockscreen_third_party_biometrics")!!.requirement!!.contains("不伪造认证成功"))
        assertTrue(featureById("notification_zen_fix")!!.requirement!!.contains("优先通知例外"))
    }

    @Test fun existingLockscreenHooksAndChargingEntryArePresent() {
        assertEquals(
            setOf("lockscreen_show_notifications", "lockscreen_keep_notifications", "lockscreen_hide_status_bar",
                "lockscreen_hide_ble_toast", "lockscreen_scramble_pin", "lockscreen_double_tap", "lockscreen_hide_zen",
                "lockscreen_hide_hint", "lockscreen_third_party_biometrics", "lockscreen_charging_info",
                "lockscreen_hide_left_shortcut", "lockscreen_hide_right_shortcut", "lockscreen_left_flashlight"),
            SYSTEM_UI_FEATURES.filter { it.group == ScopeFeatureGroup.LOCK_SCREEN }.map { it.id }.toSet(),
        )
    }

    @Test fun customSettingsAreIncludedInTheSharedSettingsCatalog() {
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_MONET_COLOR))
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_FOCUS_PACKAGES))
        assertEquals(listOf(SYSTEM_UI_MONET_COLOR), featureById("systemui_monet_custom")!!.config.map { it.key })
        assertEquals(listOf(SYSTEM_UI_FOCUS_PACKAGES), featureById("notification_unlock_focus")!!.config.map { it.key })
        assertTrue(featureById("systemui_monet_custom")!!.config.single() is HyperColor)
        assertTrue(featureById("notification_unlock_focus")!!.config.single() is HyperAppSelection)
        assertTrue(featureById(NotificationExpansionSettings.EXPAND)!!.config.single() is HyperAppSelection)
    }

    @Test fun notificationImportanceIsAnInlineSwitchWithUnchangedScopeAndKey() {
        val feature = featureById("notification_importance")!!
        assertEquals("解锁通知重要程度", feature.title)
        assertFalse(feature.hasDetailPage)
        assertNull(feature.extra)
        assertEquals(listOf("systemui", "settings"), feature.scopes)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertTrue(feature.summary.contains("系统实际值"))
        assertTrue(feature.requirement!!.contains("修复待真机验收"))
        assertTrue(feature.requirement.contains("重启系统界面"))
        assertFalse(feature.defaultEnabled)
    }

    @Test fun notificationCountLimitOnlyControlsOverflowDismissal() {
        val feature = featureById("notification_remove_count_limit")!!
        assertFalse(feature.hasDetailPage)
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.requirement!!.contains("不绕过系统服务"))
        assertTrue(feature.requirement.contains("内存"))
    }

    @Test fun mediaDarkIsAnOptInNativeOnlySwitch() {
        val feature = featureById("media_card_always_dark")!!
        assertFalse(feature.hasDetailPage)
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.requirement!!.contains("不移除锁屏/AOD 监听"))
        assertTrue(feature.requirement.contains("不含封面/渐变背景"))
    }
}
