/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFeaturesTest {
    @Test fun settingsFeaturesHaveUniqueHostsGroupsAndDefaults() {
        assertEquals(24, SETTINGS_FEATURES.size)
        assertEquals(
            SETTINGS_FEATURES.size,
            SETTINGS_FEATURES.map { it.id }.distinct().size,
        )
        for (feature in SETTINGS_FEATURES) {
            val scope = feature.entryScope
            assertNotNull(scope)
            assertEquals("settings", scope?.id)
            assertEquals(listOf("settings"), feature.scopes)
            assertEquals("settings", feature.entryGroup?.scopeId)
            assertFalse(feature.defaultEnabled)
            assertEquals(feature.config.isNotEmpty(), feature.hasDetailPage)
        }
    }

    @Test fun everySettingsFeatureHasExactlyOnePanelRow() {
        val declared = FeaturePanel.entries
            .filter { panel -> panel.featureIds.any { featureById(it)?.entryScope?.id == "settings" } }
            .flatMap { it.featureIds.toList() }
            .filter { id -> SETTINGS_FEATURES.any { it.id == id } }
        assertEquals(SETTINGS_FEATURES.map { it.id }.sorted(), declared.sorted())
        assertEquals(declared.size, declared.distinct().size)
        assertTrue(SETTINGS_FEATURES.none { it.panel == FeaturePanel.OTHER })
    }

    @Test fun newSettingsFeaturesStayInTheirSemanticPanels() {
        val expected = mapOf(
            "system_settings_view_wifi_password" to FeaturePanel.SETTINGS_CONNECTION,
            "system_settings_more_vpn_types" to FeaturePanel.SETTINGS_CONNECTION,
            "system_settings_unlock_super_resolution" to FeaturePanel.SETTINGS_DISPLAY,
            "system_settings_unlock_ai_image" to FeaturePanel.SETTINGS_DISPLAY,
            "system_settings_unlock_memc" to FeaturePanel.SETTINGS_DISPLAY,
            "system_settings_more_notifications" to FeaturePanel.SETTINGS_NOTIFICATION,
            "system_settings_accessibility_permission_direct" to FeaturePanel.SETTINGS_PERMISSION,
            "system_settings_usb_mode" to FeaturePanel.SETTINGS_DEVELOPER,
            "system_settings_unlock_taplus" to FeaturePanel.SETTINGS_HOME,
            "system_settings_anti_ques" to FeaturePanel.SETTINGS_CONNECTION,
        )
        for ((id, panel) in expected) {
            assertEquals(panel, featureById(id)?.panel)
        }
    }

    @Test fun screenTimeoutFeatureDescribesAllAdditionalChoices() {
        val feature = featureById("system_settings_allow_never_lock_screen")
        assertNotNull(feature)
        assertEquals("解锁更多自动锁屏时间", feature?.title)
        assertTrue(feature?.summary?.contains("20、30、60 分钟") == true)
        assertTrue(feature?.summary?.contains("永不") == true)
    }

    @Test fun usbModeChoiceOnlyExposesModesProvidedByCurrentHost() {
        val feature = featureById("system_settings_usb_mode")
        assertNotNull(feature)
        val choice = feature?.config?.single() as? HyperChoice
        assertNotNull(choice)
        assertEquals(SETTINGS_USB_MODE_CHOICE_KEY, choice?.key)
        assertEquals(listOf("0", "1", "2"), choice?.entries?.map { it.variant })
        assertEquals("保持原生弹窗", choice?.labelOf("0"))
        assertEquals("仅充电", choice?.labelOf("1"))
        assertEquals("传输文件", choice?.labelOf("2"))
    }
}
