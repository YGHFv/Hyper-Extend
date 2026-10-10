/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class NavigationHandleSettingsTest {
    @Test fun featureIsOptInAndSharesNavigationPanel() {
        val feature = featureById(NavigationHandleSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui"), feature.scopes)
        assertEquals(ScopeFeatureGroup.SYSTEM_UI_OTHER, feature.group)
        assertEquals(FeaturePanel.NAVIGATION, feature.panel)
        assertTrue(feature.hasDetailPage)
        assertTrue(feature.requirement!!.contains("隐藏手势横条优先"))
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        assertEquals(3, feature.config.size)
    }

    @Test fun radiusRetainsHundredthDpAndHalfTenthStep() {
        val slider = NavigationHandleSettings.radius
        assertEquals(5, slider.step)
        assertEquals("1.85 dp", slider.display(slider.default))
        assertEquals("1.90 dp", slider.display(190))
        assertEquals("0.00 dp", slider.display(0))
        assertEquals(1.85f, NavigationHandleSettings.radiusDp(""), 0f)
        assertEquals(1.9f, NavigationHandleSettings.radiusDp("190"), 0f)
    }

    @Test fun invalidAndOutOfBoundsBackupValuesAreSafe() {
        assertEquals(1.85f, NavigationHandleSettings.radiusDp("NaN"), 0f)
        assertEquals(1.85f, NavigationHandleSettings.radiusDp("99999999999999"), 0f)
        assertEquals(0f, NavigationHandleSettings.radiusDp("-100"), 0f)
        assertEquals(5f, NavigationHandleSettings.radiusDp("800"), 0f)
    }

    @Test fun bothColorEndpointsSupportTransparencyAndBlankMeansNative() {
        val colors = NavigationHandleSettings.config.filterIsInstance<HyperColor>()
        assertEquals(2, colors.size)
        assertTrue(colors.all { it.allowAlpha })
        assertNull(SystemUiCustomSettings.color("", true))
        assertEquals(0xcc000000.toInt(), SystemUiCustomSettings.color("#CC000000", true))
        assertEquals(0xffffffff.toInt(), SystemUiCustomSettings.color("#ffffff", true))
        assertEquals(0, SystemUiCustomSettings.color("#00000000", true))
    }

    @Test fun alphaRoundTripDoesNotAlterExistingOpaqueMonetContract() {
        for (color in listOf(0, 0x00112233, 0xcc000000.toInt(), 0xffffffff.toInt())) {
            assertEquals(color, SystemUiCustomSettings.color(SystemUiCustomSettings.colorText(color, true), true))
        }
        assertNull(SystemUiCustomSettings.color("#CC000000"))
        assertEquals("#112233", SystemUiCustomSettings.colorText(0x80112233.toInt()))
        assertFalse((featureById("systemui_monet_custom")!!.config.single() as HyperColor).allowAlpha)
        for (bad in listOf("#GG112233", "#12345", "#123456789", "##CC000000", "#-C000000")) {
            assertNull(bad, SystemUiCustomSettings.color(bad, true))
        }
    }

    @Test fun existingSliderFormattingDoesNotChange() {
        assertEquals("0.5 dp", HyperSlider("test", "test", 0, 10, 1, divisor = 2, unit = " dp").display(1))
        assertEquals("8.0 dp", HyperSlider("test", "test", 0, 100, 80, divisor = 10, unit = " dp").display(80))
        assertEquals("85 %", HyperSlider("test", "test", 0, 100, 85, unit = " %").display(85))
    }
}
