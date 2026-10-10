/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class MediaBackgroundSettingsTest {
    @Test fun malformedModesKeepNativeBackground() {
        for (raw in listOf(null, "", "-1", "5", "bad")) assertEquals(0, MediaBackgroundSettings.mode(raw))
        for (mode in 0..4) assertEquals(mode, MediaBackgroundSettings.mode(mode.toString()))
    }
    @Test fun optInEntryDocumentsPartialScopeAndKeepsSettingsPersisted() {
        val feature = featureById(MediaBackgroundSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertTrue(feature.requirement!!.contains("部分迁移"))
        assertTrue(feature.requirement.contains("锁屏、AOD"))
        assertTrue(feature.config.all { it.key in HyperSettings.STRING_KEYS })
        assertEquals("0", feature.config.filterIsInstance<HyperChoice>().single().entries.first().variant)
    }
    @Test fun blurRangeMatchesSupportedZeroAndBoundedProcessing() {
        val blur = MediaBackgroundSettings.config.filterIsInstance<HyperSlider>().single()
        assertEquals(0, blur.min); assertEquals(20, blur.max); assertEquals(10, blur.default)
    }
    @Test fun transitionIsAnOptInBooleanOwnedByBackgroundFeature() {
        val option = featureById(MediaBackgroundSettings.FEATURE)!!.options.single()
        assertEquals(MediaBackgroundSettings.TRANSITION, option.id)
        assertFalse(option.defaultEnabled)
        assertFalse(defaultEnabledOf(option.id))
        assertFalse(option.id in HyperSettings.STRING_KEYS)
        assertTrue(option.summary!!.contains("仅背景过渡"))
    }
}
