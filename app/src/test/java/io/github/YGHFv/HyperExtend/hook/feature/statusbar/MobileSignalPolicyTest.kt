/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class MobileSignalPolicyTest {
    private fun evaluate(
        mode: Int = 0,
        hide1: Boolean = false,
        hide2: Boolean = false,
        sub: Int = 10,
        slot: Int? = 0,
        dataSub: Int? = 10,
        airplane: Boolean? = false,
        wifi: Boolean? = false,
        data: Boolean? = false,
    ) = MobileSignalPolicy(mode, hide1, hide2).overrideVisibility(sub, slot, dataSub, airplane, wifi, data)

    @Test fun defaultPreservesOriginalVisibility() {
        for (airplane in listOf(false, true)) for (wifi in listOf(false, true)) {
            assertNull(evaluate(airplane = airplane, wifi = wifi))
        }
        assertFalse(MobileSignalPolicy(0, false, false).enabled)
    }

    @Test fun cardHidingUsesSlotNotSubscriptionId() {
        assertEquals(false, evaluate(hide1 = true, sub = 98, slot = 0))
        assertNull(evaluate(hide1 = true, sub = 0, slot = 1))
        assertEquals(false, evaluate(hide2 = true, sub = 4, slot = 1))
        assertNull(evaluate(hide2 = true, slot = 0))
        assertNull(evaluate(hide1 = true, hide2 = true, slot = -1))
        assertNull(evaluate(hide1 = true, slot = null))
        for (slot in 0..1) assertEquals(false, evaluate(hide1 = true, hide2 = true, slot = slot))
    }

    @Test fun nonWifiModeAndHiddenCardsCompose() {
        for (slot in 0..1) {
            assertEquals(true, evaluate(1, slot = slot))
            assertEquals(false, evaluate(1, slot = slot, wifi = true))
            assertEquals(false, evaluate(1, slot = slot, airplane = true))
        }
        assertEquals(false, evaluate(1, hide1 = true))
        assertEquals(false, evaluate(1, hide2 = true, slot = 1))
        assertEquals(true, evaluate(1, hide1 = true, slot = 1))
    }

    @Test fun connectedModeOnlyShowsConnectedDefaultDataSim() {
        assertEquals(false, evaluate(2))
        assertEquals(true, evaluate(2, data = true))
        assertEquals(false, evaluate(2, data = true, sub = 20, slot = 1))
        assertEquals(false, evaluate(2, wifi = true))
        assertEquals(false, evaluate(2, dataSub = -1, data = true))
    }

    @Test fun dataSimModeDoesNotDependOnConnection() {
        assertEquals(true, evaluate(3, wifi = true))
        assertEquals(false, evaluate(3, sub = 20, slot = 1))
        assertEquals(false, evaluate(3, airplane = true))
        assertEquals(false, evaluate(3, slot = -1))
        assertEquals(false, evaluate(3, sub = -1))
    }

    @Test fun dataSimModesIgnoreConflictingImportedHideFlags() {
        for (mode in 2..3) for (slot in 0..1) {
            assertEquals(true, evaluate(mode, hide1 = true, hide2 = true, slot = slot, data = true))
        }
    }

    @Test fun unknownSystemStateFallsBackRatherThanForcingVisibility() {
        assertNull(evaluate(1, wifi = null))
        for (mode in 1..3) {
            assertNull(evaluate(mode, airplane = null))
            assertNull(evaluate(mode, slot = null))
        }
        assertNull(evaluate(2, data = null))
        assertNull(evaluate(3, dataSub = null))
        assertEquals(false, evaluate(1, airplane = true, wifi = null))
    }

    @Test fun transitionsDoNotLatchOldNetworkOrSimState() {
        assertEquals(listOf(true, false, true, false, true), listOf(
            evaluate(1), evaluate(1, wifi = true), evaluate(1),
            evaluate(1, airplane = true), evaluate(1),
        ))
        assertEquals(listOf(false, true, false), listOf(
            evaluate(2), evaluate(2, data = true), evaluate(2),
        ))
        assertEquals(listOf(true, false), listOf(evaluate(3), evaluate(3, dataSub = 20)))
        assertEquals(true, evaluate(3, sub = 20, slot = 1, dataSub = 20))
        assertNull(evaluate(hide1 = true, sub = 10, slot = 1))
        assertEquals(false, evaluate(hide1 = true, sub = 20, slot = 0))
    }
}
