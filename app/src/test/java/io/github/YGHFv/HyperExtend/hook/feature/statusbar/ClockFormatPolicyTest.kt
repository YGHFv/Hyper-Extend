/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class ClockFormatPolicyTest {
    @Test fun blankDefaultsDoNotReplaceNativeFormats() {
        val policy = ClockFormatPolicy()
        assertFalse(policy.changed)
        for (name in listOf("clock", "big_time", "date_time", "pad_clock", "horizontal_time")) assertNull(policy.pattern(name, true))
    }

    @Test fun legacyFormatsRemainIndependentIncludingExistingMultilineInput() {
        val p = ClockFormatPolicy(status = "HH:mm\nss", big = "h:mm a", mini = "E", pad = "M/d")
        assertEquals("HH:mm\nss", p.pattern("clock", true))
        assertEquals("h:mm a", p.pattern("big_time", true))
        assertEquals("E", p.pattern("date_time", true))
        assertEquals("M/d", p.pattern("pad_clock", true))
        assertNull(p.pattern("horizontal_time", true))
        assertNull(p.pattern("unknown", true))
    }

    @Test fun dualLineOrderUsesFirstConfiguredLinesOnly() {
        val p = ClockFormatPolicy(style = 1, status = "HH:mm:ss\nignored", mini = "E M/d\nignored")
        assertEquals("HH:mm:ss\nE M/d", p.pattern("clock", true))
        assertEquals("E M/d\nHH:mm:ss", p.copy(style = 2).pattern("clock", true))
    }

    @Test fun dualLineFallbackHonorsNativeHourCycle() {
        val p = ClockFormatPolicy(style = 1)
        assertEquals("HH:mm\nM/d E", p.pattern("clock", true))
        assertEquals("h:mm\nM/d E", p.pattern("clock", false))
    }

    @Test fun syncingBigClockNeverCopiesDateLineOrErasesStoredBigFormat() {
        val p = ClockFormatPolicy(style = 2, syncBig = true, status = "HH:mm:ss\nE", big = "h:mm a")
        assertEquals("HH:mm:ss", p.pattern("big_time", true))
        assertEquals("h:mm a", p.copy(syncBig = false).pattern("big_time", true))
        assertNull(p.copy(status = "").pattern("big_time", true))
    }

    @Test fun hiddenPadDoesNotScheduleOrRenderCustomSeconds() {
        val p = ClockFormatPolicy(hidePad = true, pad = "HH:mm:ss", status = "HH:mm")
        assertNull(p.pattern("pad_clock", true))
        assertFalse(p.needsSeconds("pad_clock"))
        assertEquals("HH:mm", p.pattern("clock", true))
    }

    @Test fun secondsSchedulingUsesEffectivePatternAndIgnoresQuotedLiterals() {
        val p = ClockFormatPolicy(style = 1, mini = "ss", status = "HH:mm", big = "HH:mm 'seconds'")
        assertTrue(p.needsSeconds("clock"))
        assertTrue(p.needsSeconds("date_time"))
        assertFalse(p.needsSeconds("big_time"))
        assertFalse(p.needsSeconds("horizontal_time"))
        assertTrue(p.copy(syncBig = true, status = "HH:mm:ss").needsSeconds("big_time"))
    }

    @Test fun sharedCalendarIsRestoredAfterSuccessfulFormatting() {
        var time = 123L
        val result = ClockCalendarScope.atTime({ time }, { time = it }, 456) { time.toString() }
        assertEquals("456", result)
        assertEquals(123L, time)
    }

    @Test fun sharedCalendarIsRestoredAfterInvalidUserPattern() {
        var time = 123L
        val failure = IllegalArgumentException("pattern")
        val result = runCatching { ClockCalendarScope.atTime({ time }, { time = it }, 456) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(123L, time)
    }

    @Test fun calendarWriteFailureStillAttemptsOriginalTimestamp() {
        var time = 123L
        val result = runCatching { ClockCalendarScope.atTime({ time }, {
            time = it
            if (it == 456L) error("write failed")
        }, 456) { "unreachable" } }
        assertTrue(result.isFailure)
        assertEquals(123L, time)
    }

    @Test fun wrappedCustomTextDoesNotBecomeItsOwnNativeBaseline() {
        val state = ClockTextOverride()
        val native = StringBuilder("12:00")
        state.apply(native, "12:00:01")
        state.apply(StringBuilder("12:00:01"), "12:00:02")
        assertSame(native, state.restore(StringBuilder("12:00:02")))
    }

    @Test fun newerNativeTextAndRepeatedRestorationArePreserved() {
        val state = ClockTextOverride()
        state.apply("12:00", "12:00:01")
        assertEquals("12:01", state.restore("12:01"))
        assertEquals("12:02", state.restore("12:02"))
    }
}
