/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class MobileSignalSettingsTest {
    @Test fun catalogRegistersNewKeysAndReferenceLabels() {
        val feature = featureById("status_bar_mobile")!!
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui"), feature.scopes)
        val row = feature.config.filterIsInstance<HyperChoice>().single { it.key == MobileSignalSettings.MODE }
        assertEquals("0", row.default)
        assertEquals(listOf("0", "1", "2", "3"), row.entries.map { it.variant })
        assertEquals(listOf("默认", "非 WiFi 下始终显示", "仅在连接时显示", "仅显示上网卡"), row.entries.map { it.label })
        assertTrue(MobileSignalSettings.MODE in HyperSettings.STRING_KEYS)
        for (key in listOf(MobileSignalSettings.HIDE_SIM_1, MobileSignalSettings.HIDE_SIM_2)) {
            assertFalse(feature.options.single { it.id == key }.defaultEnabled)
            assertTrue(MobileSignalSettings.isHideCardOption(key))
        }
    }

    @Test fun invalidModesUseDefaultAndUiDependenciesMatchPolicy() {
        for (value in listOf(null, "", "oops", "-1", "4", "9999999999999")) {
            assertEquals(0, MobileSignalSettings.mode(value))
        }
        for (mode in 0..3) {
            assertEquals(mode, MobileSignalSettings.mode(mode.toString()))
            assertEquals(mode < 2, MobileSignalSettings.allowsHiddenCards(mode))
        }
        assertFalse(MobileSignalSettings.isHideCardOption("status_bar_mobile.hide_roaming"))
    }
}
