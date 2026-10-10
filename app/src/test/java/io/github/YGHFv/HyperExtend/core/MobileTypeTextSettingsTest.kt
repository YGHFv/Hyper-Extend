/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class MobileTypeTextSettingsTest {
    @Test fun validShortSingleLineNamesKeepTheirText() {
        assertEquals("5G+", MobileTypeTextSettings.text(" 5G+ "))
        assertEquals("12345678", MobileTypeTextSettings.text("12345678"))
        assertEquals("\uD83D\uDCF6", MobileTypeTextSettings.text("\uD83D\uDCF6"))
    }

    @Test fun blankOversizeAndInvisibleControlSequencesKeepSystemNames() {
        for (value in listOf("", "   ", "123456789", "5G\nLTE", "5\u202eG", "5\u0000G")) {
            assertNull(MobileTypeTextSettings.text(value))
        }
    }

    @Test fun noServiceAndUnknownTypeDoNotAcquireFabricatedLabels() {
        assertEquals("", MobileTypeTextSettings.replacement("", 0, "5G"))
        assertNull(MobileTypeTextSettings.replacement(null, 0, "5G"))
        assertEquals("", MobileTypeTextSettings.replacement("", -1, "5G"))
        assertEquals("5G", MobileTypeTextSettings.replacement("LTE", 3, "5G"))
    }

    @Test fun pastedBoundaryControlsAndUnicodeLineBreaksAreRejected() {
        for (value in listOf("\n5G", "5G\r\n", "\t5G", "5G\u2028LTE", "5G\u2029LTE")) {
            assertNull(MobileTypeTextSettings.text(value))
        }
    }

    @Test fun supplementaryControlsAndUnpairedSurrogatesAreRejected() {
        for (value in listOf("5G\uDB40\uDC01", "5G\uD800", "\uDC005G")) {
            assertNull(MobileTypeTextSettings.text(value))
        }
    }

    @Test fun lengthCountsCodePointsRatherThanUtf16Units() {
        val symbol = "\uD83D\uDCF6"
        assertEquals(symbol.repeat(8), MobileTypeTextSettings.text(symbol.repeat(8)))
        assertNull(MobileTypeTextSettings.text(symbol.repeat(9)))
    }

    @Test fun catalogRegistersSearchScopeAndPersistenceWithoutEnablingByDefault() {
        val feature = featureById(MobileTypeTextSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.STATUS_BAR, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.config.single() is HyperText)
        assertTrue(MobileTypeTextSettings.TEXT in HyperSettings.STRING_KEYS)
        assertTrue(searchFeatures("网络类型文本").any { it.feature.id == feature.id })
    }
}
