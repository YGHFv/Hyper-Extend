/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class NativeAndCardFeaturesTest {
    @Test fun nativeSelectorsAreIndependentOptInFeaturesWithoutComponentWrites() {
        for (id in listOf(NativeSystemUiSettings.VOLUME, NativeSystemUiSettings.POWER)) {
            val feature = featureById(id)!!
            assertFalse(feature.defaultEnabled)
            assertFalse(feature.hasDetailPage)
            assertEquals(listOf("systemui"), feature.scopes)
            assertEquals(ScopeFeatureGroup.SYSTEM_UI_OTHER, feature.group)
            assertTrue(feature.requirement!!.contains("不修改插件组件启用状态"))
            assertTrue(feature.requirement.contains("需重启系统界面"))
        }
        assertEquals(FeaturePanel.POWER, featureById(NativeSystemUiSettings.POWER)!!.panel)
        assertEquals(FeaturePanel.VOLUME, featureById(NativeSystemUiSettings.VOLUME)!!.panel)
    }

    @Test fun cardColorsDoNotAliasSmallTileSettings() {
        val feature = featureById(CardTileColorSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertEquals(listOf(CardTileColorSettings.BACKGROUND, CardTileColorSettings.FOREGROUND),
            feature.config.map { it.key })
        assertTrue(feature.config.all { it is HyperColor && !it.allowAlpha })
        assertTrue(feature.config.none { it.key in TileColorSettings.config.map { row -> row.key } })
        assertTrue(feature.requirement!!.contains("流量警告"))
        assertTrue(feature.requirement.contains("不强关材质"))
    }
}
