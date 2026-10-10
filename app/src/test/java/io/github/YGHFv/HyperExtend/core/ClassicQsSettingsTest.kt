/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

// Prepared for a later authorized run; this migration is source-review-only.
class ClassicQsSettingsTest {
    @Test fun orientationsSelectIndependentRowsAndCounts() {
        assertSame(ClassicQsSettings.rowsPortrait, ClassicQsSettings.row(1, false))
        assertSame(ClassicQsSettings.rowsLandscape, ClassicQsSettings.row(2, false))
        assertSame(ClassicQsSettings.quickPortrait, ClassicQsSettings.row(1, true))
        assertSame(ClassicQsSettings.quickLandscape, ClassicQsSettings.row(2, true))
        assertNull(ClassicQsSettings.row(0, true))
        assertNull(ClassicQsSettings.row(3, false))
    }
    @Test fun valuesKeepUpstreamDefaultsAndBounds() {
        assertEquals(listOf(3, 2, 5, 6), ClassicQsSettings.config.map { ClassicQsSettings.value("", it) })
        for (row in ClassicQsSettings.config) {
            assertEquals(row.min, ClassicQsSettings.value("-99", row))
            assertEquals(row.max, ClassicQsSettings.value("999", row))
            assertEquals(row.default, ClassicQsSettings.value("999999999999999", row))
        }
    }
    @Test fun catalogIsOptInPartialAndContainsNoFakeColumnControls() {
        val feature = featureById(ClassicQsSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.CLASSIC_CONTROL_CENTER, feature.panel)
        assertEquals(4, feature.config.size)
        assertTrue(feature.config.all { it.key in CONFIG_KEYS })
        assertTrue(feature.requirement!!.contains("不含展开列数"))
        assertTrue(feature.requirement.contains("待真机验收"))
    }
}
