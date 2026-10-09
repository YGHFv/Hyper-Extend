/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SystemUiFeaturesTest {
    @Test fun newFeaturesAreOptInAndRemainInSystemUi() {
        assertEquals(28, SYSTEM_UI_FEATURES.size)
        SYSTEM_UI_FEATURES.forEach { feature ->
            assertFalse(feature.defaultEnabled)
            assertEquals(if (feature.id == "control_center_unlock_old") listOf("systemui", "settings")
                else listOf("systemui"), feature.scopes)
            assertEquals(feature.config.isNotEmpty() || feature.options.isNotEmpty(), feature.hasDetailPage)
            assertNotNull(feature.entryGroup)
            assertTrue(feature.requirement!!.contains("重启系统界面"))
            assertTrue(feature.origin.contains("HyperCeiler"))
        }
    }

    @Test fun eachNewSubmenuContainsOnlyItsOwnFeatures() {
        for (group in listOf(ScopeFeatureGroup.LOCK_SCREEN, ScopeFeatureGroup.CONTROL_CENTER, ScopeFeatureGroup.SYSTEM_UI_OTHER)) {
            assertEquals(
                (SYSTEM_UI_FEATURES + APP_VOLUME_FEATURE).filter { it.group == group }.map { it.id },
                featuresOfScopePage("systemui", group).map { it.id },
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

    @Test fun allNineSystemUiBLockscreenHooksArePresent() {
        assertEquals(
            setOf("lockscreen_show_notifications", "lockscreen_keep_notifications", "lockscreen_hide_status_bar",
                "lockscreen_hide_ble_toast", "lockscreen_scramble_pin", "lockscreen_double_tap", "lockscreen_hide_zen",
                "lockscreen_hide_hint", "lockscreen_third_party_biometrics"),
            SYSTEM_UI_FEATURES.filter { it.group == ScopeFeatureGroup.LOCK_SCREEN }.map { it.id }.toSet(),
        )
    }

    @Test fun customSettingsAreIncludedInTheSharedSettingsCatalog() {
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_MONET_COLOR))
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_FOCUS_PACKAGES))
        assertEquals(listOf(SYSTEM_UI_MONET_COLOR), featureById("systemui_monet_custom")!!.config.map { it.key })
        assertEquals(listOf(SYSTEM_UI_FOCUS_PACKAGES), featureById("notification_unlock_focus")!!.config.map { it.key })
    }
}
