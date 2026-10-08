/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SystemUiCustomSettingsTest {
    @Test fun acceptsOnlyOpaqueRgbColors() {
        assertEquals(0xff4285f4.toInt(), SystemUiCustomSettings.color(" #4285F4 "))
        assertEquals(-1, SystemUiCustomSettings.color("ffffff"))
        assertEquals(0xff000000.toInt(), SystemUiCustomSettings.color("000000"))
        for (value in listOf("", "#fff", "#004285F4", "red", "12345g", "##123456")) {
            assertNull(value, SystemUiCustomSettings.color(value))
        }
    }

    @Test fun packageListsAreExactDeduplicatedAndNeverWildcards() {
        assertEquals(setOf("com.example.app", "org.example.app"), SystemUiCustomSettings.packages(
            "com.example.app,org.example.app;com.example.app\n * com.* .bad 123.bad bad/intent"
        ))
        assertTrue(SystemUiCustomSettings.packages("").isEmpty())
        assertTrue(SystemUiCustomSettings.packages("com.example.app").contains("com.example.app"))
        assertFalse(SystemUiCustomSettings.packages("com.example.app").contains("com.example.app2"))
    }
}
