/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class StatusBarPoliciesTest {
    @Test fun eachClockSliderActivatesIndependently() {
        assertFalse(ClockStyle().changed)
        for (style in listOf(ClockStyle(bold = true), ClockStyle(size = 13), ClockStyle(left = 1),
            ClockStyle(right = 1), ClockStyle(vertical = 13))) assertTrue(style.changed)
    }
    @Test fun onlyUnquotedSecondsNeedTicks() {
        for (pattern in listOf("HH:mm:ss", "s", "ss 's'", "''ss", "SSS")) assertTrue(pattern, ClockTickPolicy.needsSeconds(pattern))
        for (pattern in listOf("", "HH:mm", "HH:mm 'ss'", "'it''s' HH:mm", "'s")) assertFalse(pattern, ClockTickPolicy.needsSeconds(pattern))
    }
    @Test fun clockDelaysAlignToWallSecondWithoutZeroDelayLoop() {
        for (now in -2000L..3000L) {
            val delay = ClockTickPolicy.delay(now)
            assertTrue(delay in 1..1000)
            assertEquals(0L, Math.floorMod(now + delay, 1000L))
        }
    }
    @Test fun batteryMovesGroupsOnlyOnceAndLeavesMiddleChargeIndicator() {
        val children = mutableListOf("icon", "charging", "percent")
        repeat(3) {
            val icon = children.indexOf("icon"); val percent = children.indexOf("percent")
            if (BatteryOrderPolicy.shouldSwap(icon, percent)) java.util.Collections.swap(children, icon, percent)
        }
        assertEquals(listOf("percent", "charging", "icon"), children)
        assertFalse(BatteryOrderPolicy.shouldSwap(-1, 2))
        assertFalse(BatteryOrderPolicy.shouldSwap(0, -1))
    }
    @Test fun screenshotExclusionIsPerCallAndPreservesAllHostFilters() {
        val original = arrayOf("ScreenshotAnimation", "SensitiveLayer")
        val a = ScreenshotLayerPolicy.exclusions(original, false)!!
        val b = ScreenshotLayerPolicy.exclusions(a, false)!!
        assertArrayEquals(arrayOf("ScreenshotAnimation", "SensitiveLayer", "StatusBar"), a)
        assertArrayEquals(a, b)
        assertArrayEquals(arrayOf("ScreenshotAnimation", "SensitiveLayer"), original)
        assertArrayEquals(arrayOf("StatusBar"), ScreenshotLayerPolicy.exclusions(null, false))
        assertSame(original, ScreenshotLayerPolicy.exclusions(original, true))
        assertNull(ScreenshotLayerPolicy.exclusions(null, true))
    }
}
