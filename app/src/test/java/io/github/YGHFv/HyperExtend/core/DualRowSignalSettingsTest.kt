/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class DualRowSignalSettingsTest {
    @Test fun discoverableOptInFeatureUsesReferenceStylesAndMiuixConfig() {
        val feature = featureById(DualRowSignalSettings.FEATURE)!!
        assertEquals("双排移动网络图标", feature.title)
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.STATUS_BAR, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(searchFeatures("双排").any { it.feature.id == feature.id })
        assertTrue(feature.config.all { it.key in HyperSettings.STRING_KEYS })
        assertEquals(listOf("", "classic", "thick", "theme"), feature.config.filterIsInstance<HyperChoice>().single().entries.map { it.variant })
    }

    @Test fun signedOffsetsDisplayActualValuesAndMatchReferenceRanges() {
        val rows = featureById(DualRowSignalSettings.FEATURE)!!.config.filterIsInstance<HyperSlider>().associateBy { it.key }
        for (key in listOf(DualRowSignalSettings.LEFT, DualRowSignalSettings.RIGHT, DualRowSignalSettings.VERTICAL)) {
            val row = rows.getValue(key)
            assertEquals("0.0 dp", row.display(row.default))
            assertEquals("-4.0 dp", row.display(row.min))
            assertEquals("4.0 dp", row.display(row.max))
        }
    }

    @Test fun mobileVisibilityConflictsOnlyApplyWhenMobileFeatureIsEnabled() {
        assertTrue(DualRowSignalSettings.compatible(false, "3", true, true))
        assertTrue(DualRowSignalSettings.compatible(true, "", false, false))
        for (mode in listOf("1", "2", "3")) assertFalse(DualRowSignalSettings.compatible(true, mode, false, false))
        assertFalse(DualRowSignalSettings.compatible(true, "0", true, false))
        assertFalse(DualRowSignalSettings.compatible(true, "0", false, true))
        assertEquals("", DualRowSignalSettings.style("broken"))
    }
}
