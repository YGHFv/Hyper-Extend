/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「核心破解」与西米露保持一致：单一总开关 +「常规 / 扩展」两组子项。 */
class SystemFrameworkCorePatchFeatureTest {
    private val feature = featureById("system_framework_core_patch")!!

    @Test
    fun masterSwitchOwnsRegularAndExtendedGroups() {
        assertTrue(feature.hasDetailPage)
        assertEquals("核心破解", feature.title)
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("常规", "扩展"), featureDetailPanels(feature).map { it.title })
        assertEquals(8, featureDetailPanels(feature)[0].options.size)
        assertEquals(6, featureDetailPanels(feature)[1].options.size)
    }

    @Test
    fun everyLegacySwitchStaysAsAnOptionOfTheMasterSwitch() {
        val expected = setOf(
            "system_framework_core_patch_downgrade",
            "system_framework_core_patch_signature",
            "system_framework_core_patch_digest",
            "system_framework_core_patch_override_signature",
            "system_framework_core_patch_shared_user",
            "system_framework_core_patch_use_pre_signature",
            "system_framework_core_patch_exact_signature",
            "system_framework_use_android_package_installer",
            "system_framework_core_patch_disable_low_api_check",
            "system_framework_core_patch_disable_persistent",
            "system_framework_core_patch_verification_agent",
            "system_framework_core_patch_bypass_isolation_violation",
            "system_framework_core_patch_allow_update_system_app",
            "system_framework_core_patch_fingerprint_guard",
        )
        assertEquals(expected, feature.options.map { it.id }.toSet())
        // 子项默认关闭：避免「只开总开关」就把全部安装限制一起放宽。
        assertTrue(feature.options.all { !it.defaultEnabled && !defaultEnabledOf(it.id) })
    }
}
