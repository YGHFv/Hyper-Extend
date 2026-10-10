/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class ClockSettingsTest {
    @Test fun onlyAuditedClockIdsHaveStyles() {
        assertEquals(ClockSettings.status, ClockSettings.role("clock"))
        assertEquals(ClockSettings.big, ClockSettings.role("big_time"))
        assertEquals(ClockSettings.mini, ClockSettings.role("date_time"))
        assertEquals(ClockSettings.mini, ClockSettings.role("horizontal_time"))
        assertEquals(ClockSettings.pad, ClockSettings.role("pad_clock"))
        assertNull(ClockSettings.role("normal_control_center_date_view"))
        assertNull(ClockSettings.role("lockscreen_clock"))
    }

    @Test fun allLegacyKeysAndDefaultsRemainRegistered() {
        val feature = featureById(ClockSettings.FEATURE)!!
        val keys = feature.config.map { it.key }
        for (suffix in listOf("size", "left_margin", "right_margin", "vertical_offset", "editor_s", "editor_b", "editor_n", "editor_p")) {
            assertTrue("status_bar_clock.$suffix" in keys)
        }
        assertTrue(feature.options.any { it.id == "status_bar_clock.bold" && !it.defaultEnabled })
        assertEquals(12, feature.config.filterIsInstance<HyperSlider>().single { it.key == "status_bar_clock.vertical_offset" }.default)
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.options.all { !it.defaultEnabled })
        assertTrue(keys.all { it in HyperSettings.STRING_KEYS })
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun bigClockHasItsOwnRangeAndNoImplicitStatusFontOverride() {
        assertEquals(50, ClockSettings.big.defaultSize)
        assertEquals(12, ClockSettings.status.defaultSize)
        assertEquals(66, ClockSettings.big.maxSize)
        assertEquals(20, ClockSettings.status.maxSize)
    }

    @Test fun newOffsetsAreSignedAndSpacingIsDimensionless() {
        val sliders = ClockSettings.config.filterIsInstance<HyperSlider>()
        for (role in listOf(ClockSettings.big, ClockSettings.mini, ClockSettings.pad)) {
            val offset = sliders.single { it.key == role.key("vertical_offset") }
            assertEquals(0, offset.default)
            assertEquals("-6.0 dp", offset.display(offset.min))
            assertEquals("6.0 dp", offset.display(offset.max))
        }
        val spacing = sliders.single { it.key == ClockSettings.SPACING }
        assertEquals(20, spacing.divisor)
        assertEquals(16, spacing.default)
        assertFalse(spacing.unit.contains("dp"))
    }

    @Test fun invalidChoicesKeepIndependentSingleLineDefaults() {
        for (raw in listOf(null, "", "bad", "-1", "3")) assertEquals(0, ClockSettings.choice(raw, 2))
        assertEquals(2, ClockSettings.choice("2", 2))
        assertEquals(0, ClockSettings.choice("2", 1))
        val sync = ClockSettings.config.filterIsInstance<HyperChoice>().single { it.key == ClockSettings.SYNC }
        assertEquals("0", sync.entries.first().variant)
    }
}
