/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class TileColorSettingsTest {
    @Test fun colorIsOptInAndIndependentOfCorners() {
        val feature = featureById(TileColorSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertEquals(TileColorSettings.config, feature.config)
        assertTrue(TileColorSettings.BACKGROUND in CONFIG_KEYS)
        assertEquals(2, feature.config.size)
        assertTrue(TileColorSettings.ICON in CONFIG_KEYS)
        feature.config.forEach { assertFalse((it as HyperColor).allowAlpha) }
        assertTrue(feature.requirement!!.contains("可独立使用"))
        assertTrue(feature.requirement.contains("待真机验收"))
    }
    @Test fun blankInvalidAndTransparentInputStayNative() {
        assertNull(SystemUiCustomSettings.color(""))
        assertNull(SystemUiCustomSettings.color("broken"))
        assertNull(SystemUiCustomSettings.color("#00123456"))
        assertEquals(0xff123456.toInt(), SystemUiCustomSettings.color("#123456"))
    }
}
