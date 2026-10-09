/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class NetworkSpeedPolicyTest {
    @Test fun everyStyleAndHiddenBranchSatisfiesTwoSlotHostContract() {
        for (style in 0..4) for (hide in listOf(false, true)) for (all in listOf(false, true)) {
            for (tx in listOf(0L, 1024L, 65536L, 1048576L)) for (rx in listOf(0L, 1024L, 65536L, 1048576L)) {
                val native = arrayOf("native", "unit")
                val output = NetworkSpeedText.render(tx, rx, NetworkSpeedText.Options(style, hide = hide, hideAll = all)) ?: native
                assertEquals(2, output.size)
                // Model the audited host's unconditional index reads, not just size inspection.
                assertNotNull(output[0] + output[1])
                if (style == 0 && (!hide || tx + rx >= 65536)) assertSame(native, output)
                if (style != 0) assertEquals("", output[1])
            }
        }
    }

    @Test fun styleShapesUnitsArrowsAndSwappingStayDistinct() {
        fun text(style: Int, swap: Boolean = false, suffix: String = "B/s") =
            NetworkSpeedText.render(1048576, 2097152, NetworkSpeedText.Options(style, icon = 5, swap = swap, suffix = suffix))!![0]
        assertEquals("3.0MB/s", text(1))
        assertEquals("3.0\nMB/s", text(2))
        assertEquals("1.0MB/s\u2191 2.0MB/s\u2193", text(3))
        assertEquals("\u21911.0M\n\u21932.0M", text(4, true, ""))
        for (style in 1..4) assertEquals("", NetworkSpeedText.render(0, 0,
            NetworkSpeedText.Options(style, hide = true))!![0])
    }

    @Test fun samplersAreIndependentAndResetAfterSleepOrCounterReset() {
        val a = NetworkSpeedSampler(); val b = NetworkSpeedSampler()
        assertEquals(0L to 0L, a.sample(1, 100, 200))
        assertEquals(1000L to 2000L, a.sample(1_000_000_001, 1100, 2200))
        assertEquals(0L to 0L, b.sample(1_000_000_001, 1100, 2200))
        assertEquals(1000L to 2000L, a.sample(1_010_000_001, 1110, 2220))
        assertEquals(0L to 0L, a.sample(61_000_000_001, 1000000, 2000000))
        assertEquals(0L to 0L, a.sample(62_000_000_001, 0, 0))
        assertEquals(0L to 0L, a.sample(63_000_000_001, -1, -1))
        assertEquals(0L to 0L, a.sample(64_000_000_001, 100, 200))
    }

    @Test fun onlyPendingBackgroundSamplingIsRescheduled() {
        for (id in listOf(100004, 100005, 200001, -1)) for (bg in listOf(false, true)) {
            for (hidden in listOf(false, true)) for (pending in listOf(false, true)) {
                assertEquals(id == 200001 && bg && !hidden && pending,
                    NetworkSpeedSchedule.shouldReschedule(id, bg, hidden, pending))
            }
        }
    }

    @Test fun maximumTenSecondCadenceToleratesDispatchJitter() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(1, 0, 0)
        assertEquals(1000L to 2000L, sampler.sample(10_100_000_001, 10100, 20200))
    }

    @Test fun simulatedQueuesKeepUiPayloadAndStopWhenHostStops() {
        val ui = mutableListOf(100004 to 1234L)
        val bg = mutableListOf(4000L)
        if (NetworkSpeedSchedule.shouldReschedule(200001, true, false, bg.isNotEmpty())) {
            bg.clear(); bg += 1000L
        }
        assertEquals(listOf(100004 to 1234L), ui)
        assertEquals(listOf(1000L), bg)
        bg.clear()
        assertFalse(NetworkSpeedSchedule.shouldReschedule(200001, true, true, false))
        assertFalse(NetworkSpeedSchedule.shouldReschedule(200001, true, false, false))
    }
}
