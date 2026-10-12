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
    @Test fun batterySwapsFreshNativeLayoutWithoutAccumulatingOffsets() {
        repeat(3) {
            assertEquals(30 to 0, BatteryPositionPolicy.swap(50, 0, 20, 20, 50, false))
        }
        assertNull(BatteryPositionPolicy.swap(50, 30, 50, 0, 30, false))
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
    @Test fun screenshotEligibilityIsVersionBoundAndNeverChangesRearOrNativeDuplicates() {
        assertTrue(ScreenshotLayerPolicy.eligible(10603038, 0, true, emptyList()))
        assertFalse(ScreenshotLayerPolicy.eligible(10603039, 0, true, emptyList()))
        assertFalse(ScreenshotLayerPolicy.eligible(10603038, 1, true, emptyList()))
        assertFalse(ScreenshotLayerPolicy.eligible(10603038, 0, false, emptyList()))
        assertFalse(ScreenshotLayerPolicy.eligible(10603038, 0, true, listOf("RearScreenshotWindow")))
        val native = arrayOf("Sensitive", "Sensitive", "StatusBar")
        assertSame(native, ScreenshotLayerPolicy.exclusions(native, false))
        assertArrayEquals(arrayOf("Sensitive", "Sensitive", "StatusBar"),
            ScreenshotLayerPolicy.exclusions(arrayOf("Sensitive", "Sensitive"), false))
    }
    @Test fun screenshotUsesOnlyTheExactCurrentStrategyAndFrameworkContracts() {
        val signatures = javaClass.getResourceAsStream("/screenshot-layer-cached-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        fun descriptor(type: String): String = when {
            type == "int" -> "I"
            type.startsWith("[") -> type.replace('.', '/')
            else -> "L${type.replace('.', '/')};"
        }
        val t = ScreenshotCaptureTargets
        for (method in listOf(t.capture, t.caller, t.exclude, t.mode)) {
            val signature = descriptor(method.owner) + "->" + method.name +
                "(" + method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        assertFalse(t.capture.isStatic)
        assertTrue(t.caller.isStatic)
        assertEquals(10, signatures.size)
    }
}
