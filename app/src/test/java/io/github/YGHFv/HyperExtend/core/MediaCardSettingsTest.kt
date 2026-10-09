/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class MediaCardSettingsTest {
    @Test fun allMediaControlsAreRegisteredAndOptIn() {
        val layout = featureById(MediaCardSettings.LAYOUT)!!
        val text = featureById(MediaCardSettings.TEXT_SIZE)!!
        assertFalse(layout.defaultEnabled)
        assertFalse(text.defaultEnabled)
        assertTrue(layout.options.all { !it.defaultEnabled })
        assertTrue(layout.hasDetailPage && text.hasDetailPage)
        (layout.config + text.config).forEach { assertTrue(CONFIG_KEYS.contains(it.key)) }
        assertEquals(listOf(MediaCardSettings.titleMargin, MediaCardSettings.artistMargin, MediaCardSettings.buttonSize, MediaCardSettings.customButtonSize), layout.config.filterIsInstance<HyperSlider>())
        assertEquals(listOf(MediaCardSettings.titleSize, MediaCardSettings.artistSize, MediaCardSettings.timeSize), text.config)
    }

    @Test fun missingInvalidAndOutOfRangeNumbersUseCatalogBounds() {
        for (slider in listOf(MediaCardSettings.titleMargin, MediaCardSettings.artistMargin,
            MediaCardSettings.titleSize, MediaCardSettings.artistSize, MediaCardSettings.timeSize)) {
            assertEquals(slider.default, MediaCardSettings.value("", slider))
            assertEquals(slider.default, MediaCardSettings.value("NaN", slider))
            assertEquals(slider.default, MediaCardSettings.value("9999999999999", slider))
            assertEquals(slider.min, MediaCardSettings.value("-999", slider))
            assertEquals(slider.max, MediaCardSettings.value("99999", slider))
            assertEquals(slider.default, MediaCardSettings.value(slider.default.toString(), slider))
        }
    }

    @Test fun unknownModesDoNotAccidentallyHideOrReorderAnything() {
        for (raw in listOf("", "oops", "-1", "3", "1000")) assertEquals(0, MediaCardSettings.mode(raw))
        assertEquals(1, MediaCardSettings.mode("1"))
        assertEquals(2, MediaCardSettings.mode("2"))
    }

    @Test fun unitsAndDefaultsMatchTheHookRatherThanUpstreamXmlTypos() {
        assertEquals("18.0 sp", MediaCardSettings.titleSize.display(180))
        assertEquals("4.0 dp", MediaCardSettings.artistMargin.display(40))
        assertTrue(featureById(MediaCardSettings.TEXT_SIZE)!!.requirement!!.contains("翻折外屏"))
    }
}
