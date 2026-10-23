/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityComponentFeaturesTest {
    @Test fun securityComponentFeaturesHaveUniqueHostsAndGroups() {
        assertEquals(77, SECURITY_COMPONENT_FEATURES.size)
        assertEquals(
            SECURITY_COMPONENT_FEATURES.size,
            SECURITY_COMPONENT_FEATURES.map { it.id }.distinct().size,
        )
        for (feature in SECURITY_COMPONENT_FEATURES) {
            val scope = feature.entryScope
            assertNotNull(scope)
            assertEquals(listOf(scope?.id), feature.scopes)
            assertEquals(scope?.id, feature.entryGroup?.scopeId)
            assertEquals(feature.config.isNotEmpty(), feature.hasDetailPage)
        }
    }

    @Test fun configurableSecurityFeaturesExposeOnlyExpectedTextRows() {
        val installSource = featureById("package_installer_install_source")!!
        assertEquals(
            listOf(PACKAGE_INSTALLER_SOURCE_PACKAGE_KEY),
            installSource.config.filterIsInstance<HyperText>().map { it.key },
        )
        val device = featureById("market_device_modify")!!
        assertEquals(
            listOf(
                MARKET_DEVICE_DEVICE_KEY,
                MARKET_DEVICE_MODEL_KEY,
                MARKET_DEVICE_MANUFACTURER_KEY,
            ),
            device.config.filterIsInstance<HyperText>().map { it.key },
        )
        val sort = featureById("security_center_app_default_sort")!!
        val sortChoice = sort.config.filterIsInstance<HyperChoice>().single()
        assertEquals(SECURITY_CENTER_APP_SORT_KEY, sortChoice.key)
        assertEquals(listOf("1", "2", "3", "4", "5"), sortChoice.entries.map { it.variant })
        assertEquals(
            listOf("运行中", "应用名称", "使用频率", "占用空间", "安装时间"),
            sortChoice.entries.map { it.label },
        )
        val sidebarColor = featureById("security_center_sidebar_line_color")!!
        assertEquals(
            listOf(
                SECURITY_CENTER_SIDEBAR_LINE_DEFAULT_KEY,
                SECURITY_CENTER_SIDEBAR_LINE_DARK_KEY,
                SECURITY_CENTER_SIDEBAR_LINE_LIGHT_KEY,
            ),
            sidebarColor.config.filterIsInstance<HyperColor>().map { it.key },
        )
    }

    @Test fun newSecurityCenterGroupsKeepTheirConfirmedFeatureSets() {
        val expected = mapOf(
            ScopeFeatureGroup.SECURITY_CENTER_APPS to listOf(
                "security_center_app_default_sort",
                "security_center_app_restrict",
                "security_center_app_disable",
                "security_center_app_details",
                "security_center_app_default_setting",
                "security_center_aosp_app_info",
                "security_center_aosp_app_manager",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_SIDEBAR_VIDEO to listOf(
                "security_center_disable_remove_screen_hold_on",
                "security_center_remove_vb_temp_limit",
                "security_center_dolby_open",
                "security_center_unlock_memc",
                "security_center_unlock_s_resolution",
                "security_center_unlock_enhance_contours",
                "security_center_unlock_new_vb",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_SIDEBAR_GAME to listOf(
                "security_center_game_performance_wild_mode",
                "security_center_remove_game_toast",
                "security_center_unlock_gun_service",
                "security_center_remove_macro_black_list",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_BATTERY to listOf(
                "security_center_unlock_low_temp_endurance",
                "security_center_show_battery_temperature",
                "security_center_show_battery_health",
                "security_center_unlock_smart_charge",
                "security_center_unlock_super_wireless_charge",
                "security_center_unlock_screen_time",
                "security_center_power_consumption_ranking",
                "security_center_show_more_battery_info",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_BUBBLES to listOf(
                "security_center_remove_conversation_bubble_settings_restriction",
                "security_center_unlock_side_hide_freeform",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_BEAUTY to listOf(
                "security_center_beauty_light_auto",
                "security_center_beauty_face",
                "security_center_beauty_privacy",
                "security_center_beauty_pc",
            ),
            ScopeFeatureGroup.SECURITY_CENTER_SIDEBAR_APPEARANCE to listOf(
                "security_center_sidebar_line_color",
            ),
        )
        expected.forEach { (group, ids) ->
            assertEquals(
                ids,
                SECURITY_COMPONENT_FEATURES
                    .filter { it.entryGroup == group }
                    .map { it.id },
            )
        }
    }

    @Test fun securityComponentHostsAreVisibleOnHome() {
        val ids = entryScopes().map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                setOf(
                    "securitycenter",
                    "packageinstaller",
                    "guardprovider",
                    "joyose",
                    "trustservice",
                    "cloudservice",
                    "analytics",
                    "htmlviewer",
                    "market",
                ),
            ),
        )
    }
}
