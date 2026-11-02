/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.core.StatusBarGestureSettings.Action
import org.junit.Assert.*
import org.junit.Test

class StatusBarGestureSettingsTest {
    @Test fun oldSwitchAndDoubleSleepRemainCompatible() {
        val feature = featureById(StatusBarGestureSettings.FEATURE)!!
        assertEquals("status_bar_double_tap", feature.id)
        assertEquals("自定义状态栏点击动作", feature.title)
        assertEquals(StatusBarGestureSettings.config, feature.config)
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        assertEquals(StatusBarGestureSettings.Selection(Action.DEFAULT, Action.SLEEP),
            StatusBarGestureSettings.selection("", ""))
        assertFalse(feature.defaultEnabled)
    }

    @Test fun choiceDefaultsMatchHookFallbackAndAllActionsAreSelectable() {
        val choices = StatusBarGestureSettings.config.map { it as HyperChoice }
        assertEquals(listOf("0", "3"), choices.map { it.default })
        for (choice in choices) {
            assertEquals(Action.entries.map { it.value }.toSet(), choice.entries.map { it.variant }.toSet())
            assertEquals(13, choice.entries.size)
        }
    }

    @Test fun explicitDefaultUnknownAndDisabledDoNotBecomeSleep() {
        assertFalse(StatusBarGestureSettings.selection("0", "0").custom)
        assertFalse(StatusBarGestureSettings.selection("broken", "999").custom)
        assertEquals(Action.DISABLED, StatusBarGestureSettings.selection("11", "11").double)
        assertTrue(StatusBarGestureSettings.selection("11", "11").custom)
        assertEquals(Action.SCROLL_TOP, StatusBarGestureSettings.selection("0", "10").double)
    }
}
