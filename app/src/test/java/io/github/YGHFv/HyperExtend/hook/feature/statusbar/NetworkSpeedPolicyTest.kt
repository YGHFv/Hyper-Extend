/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class NetworkSpeedPolicyTest {
    @Test fun nativeLoopbackTrafficIsExcludedIndependentlyInBothDirections() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(1, 10000, 20000, 4000, 5000)
        assertFalse(sampler.valid)
        assertEquals(1000L to 2000L,
            sampler.sample(1_000_000_001, 14000, 25000, 7000, 8000))
        assertTrue(sampler.valid)
        assertEquals(0L to 0L,
            sampler.sample(2_000_000_001, 19000, 30000, 12000, 13000))
        assertTrue(sampler.valid)
    }

    @Test fun unavailableLoopbackAndIncoherentSnapshotsRetainNativeText() {
        for ((sent, received) in listOf(-1L to 0L, 0L to -1L, 1001L to 0L, 0L to 1001L)) {
            val sampler = NetworkSpeedSampler()
            sampler.sample(1, 100, 100, 10, 10)
            sampler.sample(1_000_000_001, 1000, 1000, sent, received)
            assertFalse(sampler.valid)
            sampler.sample(2_000_000_001, 2000, 2000, 100, 100)
            assertFalse(sampler.valid)
            assertEquals(900L to 800L,
                sampler.sample(3_000_000_001, 3000, 3000, 200, 300))
            assertTrue(sampler.valid)
        }
    }

    @Test fun loopbackResetOrIncoherentDeltaNeverProducesASpike() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(1, 10000, 10000, 1000, 1000)
        assertEquals(100L to 100L,
            sampler.sample(1_000_000_001, 10200, 10200, 1100, 1100))
        sampler.sample(1_010_000_001, 10300, 10300, 0, 0)
        assertFalse(sampler.valid)
        assertEquals(900L to 900L,
            sampler.sample(2_010_000_001, 11300, 11300, 100, 100))
        sampler.sample(3_010_000_001, 11400, 11400, 300, 300)
        assertFalse(sampler.valid)
        assertEquals(500L to 500L,
            sampler.sample(4_010_000_001, 12400, 12400, 800, 800))
        assertTrue(sampler.valid)
    }

    @Test fun saturatedCountersWithLoopbackDoNotOverflowBeforeSubtraction() {
        val sampler = NetworkSpeedSampler()
        sampler.sample(1, Long.MAX_VALUE - 10000, Long.MAX_VALUE - 10000,
            Long.MAX_VALUE - 20000, Long.MAX_VALUE - 20000)
        assertEquals(2000L to 3000L, sampler.sample(1_000_000_001,
            Long.MAX_VALUE - 6000, Long.MAX_VALUE - 4000,
            Long.MAX_VALUE - 18000, Long.MAX_VALUE - 17000))
        assertTrue(sampler.valid)
    }

    @Test fun exactLoopbackCounterSignaturesAreStaticLongQueries() {
        val signatures = javaClass.getResourceAsStream("/network-speed-loopback-host-calls.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(2, NetworkSpeedTargets.counters.size)
        for (method in NetworkSpeedTargets.counters) {
            assertTrue(method.isStatic)
            assertEquals("long", method.returns)
            assertEquals(listOf("java.lang.String"), method.parameters)
            val signature = "L${method.owner.replace('.', '/')};->${method.name}(Ljava/lang/String;)J"
            assertTrue(signature, signature in signatures)
        }
    }

    @Test fun invalidOrInitialCountersRetainNativeUntilAValidIntervalExists() {
        assertEquals(0.8f, NetworkSpeedText.lineSpacing(16), 0.0001f)
        assertEquals(0.7f, NetworkSpeedText.lineSpacing(-1), 0.0001f)
        assertEquals(1.1f, NetworkSpeedText.lineSpacing(100), 0.0001f)
        assertFalse(NetworkSpeedText.hideUnit(1, "KB/s"))
        assertFalse(NetworkSpeedText.hideUnit(1, null))
        assertTrue(NetworkSpeedText.hideUnit(1, ""))
        assertFalse(NetworkSpeedText.hideUnit(0, ""))
        val sampler = NetworkSpeedSampler()
        sampler.sample(1, 100, 100)
        assertFalse(sampler.valid)
        sampler.sample(10_000_001, 120, 120)
        assertFalse(sampler.valid)
        sampler.sample(1_000_000_001, 1100, 2100)
        assertTrue(sampler.valid)
        sampler.sample(1_010_000_001, 1110, 2120)
        assertTrue(sampler.valid)
        sampler.sample(2_000_000_001, -1, 2140)
        assertFalse(sampler.valid)
        sampler.sample(3_000_000_001, 0, 0)
        assertFalse(sampler.valid)
        sampler.sample(4_000_000_001, 0, 0)
        assertTrue(sampler.valid)
    }

    @Test fun totalOverflowCannotMasqueradeAsSlowOrZeroTraffic() {
        val native = NetworkSpeedText.render(Long.MAX_VALUE, Long.MAX_VALUE,
            NetworkSpeedText.Options(0, hide = true, lowLevel = Long.MAX_VALUE))
        assertNull(native)
        val custom = NetworkSpeedText.render(Long.MAX_VALUE, Long.MAX_VALUE,
            NetworkSpeedText.Options(1, hide = true, lowLevel = Long.MAX_VALUE))!!
        assertTrue(custom[0].endsWith("GB/s"))
        assertFalse(custom[0].startsWith("-"))
        assertEquals("", custom[1])
    }

    @Test fun exactHostRefreshAndHandlerSignaturesAreComplete() {
        fun descriptor(type: String): String = when (type) {
            "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
            else -> if (type.startsWith("[")) type.replace('.', '/') else "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/network-speed-current-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in NetworkSpeedTargets.methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in NetworkSpeedTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertEquals(18, signatures.size)
    }
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
