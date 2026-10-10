/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class TileCornerSettingsTest {
    @Test fun pixelsKeepUpstreamRangeAndDefaultWithoutDensityConversion() {
        assertEquals(72, TileCornerSettings.value(""))
        assertEquals(72, TileCornerSettings.value("NaN"))
        assertEquals(1, TileCornerSettings.value("-2"))
        assertEquals(99, TileCornerSettings.value("100"))
        assertEquals(40, TileCornerSettings.value("40"))
    }
    @Test fun catalogIsOptInAndHonestAboutUnbuiltPartialScope() {
        val feature = featureById(TileCornerSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertEquals(TileCornerSettings.config, feature.config)
        assertTrue(TileCornerSettings.radius.key in CONFIG_KEYS)
        assertTrue(feature.requirement!!.contains("部分迁移"))
        assertTrue(feature.requirement.contains("待真机验收"))
        assertTrue(feature.requirement.contains("不强关系统材质"))
    }
}
