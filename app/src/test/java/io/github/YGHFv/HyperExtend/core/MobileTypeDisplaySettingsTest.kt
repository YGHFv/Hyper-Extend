/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class MobileTypeDisplaySettingsTest {
    @Test fun modesValidateWithoutEnablingUnknownValues() {
        for (mode in 0..4) assertEquals(mode, MobileTypeDisplaySettings.mode("$mode"))
        for (raw in listOf(null, "", "-1", "5", "bad")) assertEquals(0, MobileTypeDisplaySettings.mode(raw))
    }

    @Test fun catalogRegistersEverySettingAndKeepsFeatureOffByDefault() {
        val feature = featureById(MobileTypeDisplaySettings.FEATURE)!!
        assertEquals(listOf("systemui"), feature.scopes)
        assertEquals(ScopeFeatureGroup.STATUS_BAR, feature.entryGroup)
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.options.all { !it.defaultEnabled })
        assertTrue(feature.config.all { it.key in HyperSettings.STRING_KEYS })
        assertEquals(5, (feature.config.first() as HyperChoice).entries.size)
        assertEquals(3, feature.options.size)
        assertTrue(searchFeatures("移动网络类型显示").any { it.feature.id == feature.id })
    }

    @Test fun signedOffsetAndHalfDpSizeUseCorrectUnits() {
        val rows = featureById(MobileTypeDisplaySettings.FEATURE)!!.config.filterIsInstance<HyperSlider>()
        val offset = rows.single { it.key == MobileTypeDisplaySettings.VERTICAL }
        assertEquals("-4.0 dp", offset.display(offset.min))
        assertEquals("4.0 dp", offset.display(offset.max))
        val size = rows.single { it.key == MobileTypeDisplaySettings.SIZE }
        assertEquals("13.5 dp", size.display(size.default))
    }
}
