/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class DualRowSignalPolicyTest {
    private val sims = listOf(DualRowSignalPolicy.Sim(10, 0), DualRowSignalPolicy.Sim(20, 1))
    private val signals = listOf(DualRowSignalPolicy.Signal(10, true, 4, false), DualRowSignalPolicy.Signal(20, true, 2, false))

    @Test fun defaultDataSimOwnsUpperRowAndSwitchesWithoutStaleLevels() {
        assertEquals(DualRowSignalPolicy.Rows(20, 10, 2, 4), DualRowSignalPolicy.rows(sims, signals, 20, false))
        assertEquals(DualRowSignalPolicy.Rows(10, 20, 4, 2), DualRowSignalPolicy.rows(sims, signals, 10, false))
        assertEquals(DualRowSignalPolicy.Rows(10, 20, 4, 2), DualRowSignalPolicy.rows(sims.reversed(), signals.reversed(), -1, false))
    }

    @Test fun oneSimUnknownAirplaneAndDuplicateSubscriptionsKeepNativeLayout() {
        assertNull(DualRowSignalPolicy.rows(sims.take(1), signals, 10, false))
        for (airplane in listOf(null, true)) assertNull(DualRowSignalPolicy.rows(sims, signals, 10, airplane))
        assertNull(DualRowSignalPolicy.rows(listOf(sims[0], sims[0]), signals, 10, false))
        assertNull(DualRowSignalPolicy.rows(listOf(sims[0], sims[1].copy(slot = 0)), signals, 10, false))
        assertNull(DualRowSignalPolicy.rows(listOf(sims[0], sims[1].copy(slot = -1)), signals, 10, false))
    }

    @Test fun simReplacementDoesNotReuseSignalFromPriorSubscriptionInSameSlot() {
        val replacement = listOf(sims[0], DualRowSignalPolicy.Sim(30, 1))
        assertNull(DualRowSignalPolicy.rows(replacement, signals, 10, false))
        val current = listOf(signals[0], DualRowSignalPolicy.Signal(30, true, 0, false))
        assertEquals(0, DualRowSignalPolicy.rows(replacement, current, 10, false)!!.lowerLevel)
    }

    @Test fun unsupportedAndHiddenStatesNeverBecomeFakeReception() {
        for (signal in listOf(signals[1].copy(visible = false), signals[1].copy(level = null),
            signals[1].copy(level = 5), signals[1].copy(satellite = true), signals[1].copy(satellite = null))) {
            assertNull(DualRowSignalPolicy.rows(sims, listOf(signals[0], signal), 10, false))
        }
    }

    @Test fun ordinaryHostResourcesMapExactlyToFourBars() {
        for (level in 0..4) for (suffix in listOf("", "_darkmode", "_tint")) {
            assertEquals(level, DualRowSignalPolicy.level("stat_sys_signal_$level$suffix"))
        }
        assertEquals(listOf(0, 1, 3, 4, 5), (0..4).map(DualRowSignalPolicy::assetLevel))
        for (name in listOf("stat_sys_signal_5", "stat_sys_signal_4_no_voice", "stat_sys_signal_flightmode", "stat_sys_signal_null", "satellite_4")) {
            assertNull(DualRowSignalPolicy.level(name))
        }
    }
}
