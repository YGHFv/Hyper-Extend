/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class MobileTypeDisplayPolicyTest {
    private val hidden = MobileTypeDisplayPolicy.Native(false, false, false)
    private val small = MobileTypeDisplayPolicy.Native(true, false, false)
    private val large = MobileTypeDisplayPolicy.Native(false, true, false)
    private val special = MobileTypeDisplayPolicy.Native(false, false, true)
    private val state = MobileTypeDisplayPolicy.State(small, "LTE", true, true, false, true, false, true)

    @Test fun defaultWithoutSeparateLeavesEveryNativeFlowUntouched() {
        assertNull(MobileTypeDisplayPolicy.resolve(0, false, state))
        assertNull(MobileTypeDisplayPolicy.resolve(99, true, state))
    }

    @Test fun defaultSeparateTransfersVisibilityWithoutDuplicatingArtwork() {
        for (native in listOf(small, large, special)) assertEquals(large,
            MobileTypeDisplayPolicy.resolve(0, true, state.copy(native = native)))
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(0, true, state.copy(native = hidden)))
    }

    @Test fun alwaysShowStillRequiresServiceAndNonEmptyName() {
        assertEquals(small, MobileTypeDisplayPolicy.resolve(1, false, state.copy(native = hidden, wifi = true)))
        for (unavailable in listOf(state.copy(inService = false), state.copy(name = ""), state.copy(visible = false))) {
            assertEquals(hidden, MobileTypeDisplayPolicy.resolve(1, true, unavailable))
        }
    }

    @Test fun unsupportedOrUnknownStatesPreserveNative() {
        for (unknown in listOf(state.copy(cellular = false), state.copy(cellular = null), state.copy(inService = null),
            state.copy(satellite = true), state.copy(satellite = null), state.copy(visible = null), state.copy(native = null), state.copy(name = null))) {
            assertNull(MobileTypeDisplayPolicy.resolve(1, true, unknown))
        }
    }

    @Test fun nonWifiModeUsesKnownWifiStateOnly() {
        assertEquals(large, MobileTypeDisplayPolicy.resolve(2, true, state))
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(2, true, state.copy(wifi = true)))
        assertNull(MobileTypeDisplayPolicy.resolve(2, true, state.copy(wifi = null)))
    }

    @Test fun hideHasPriorityOverSeparateAndServiceState() {
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(3, true, state.copy(cellular = false, inService = null)))
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(3, false, state.copy(native = special)))
    }

    @Test fun connectedModeRequiresThisSimsDataAndNoWifi() {
        assertEquals(small, MobileTypeDisplayPolicy.resolve(4, false, state))
        for (unavailable in listOf(state.copy(data = false), state.copy(wifi = true), state.copy(wifi = null, data = false))) {
            assertEquals(hidden, MobileTypeDisplayPolicy.resolve(4, false, unavailable))
        }
        assertNull(MobileTypeDisplayPolicy.resolve(4, false, state.copy(data = null)))
        assertNull(MobileTypeDisplayPolicy.resolve(4, false, state.copy(wifi = null)))
    }

    @Test fun policiesKeepNativeLargeAndSpecialVariantsWhenNotSeparating() {
        assertEquals(large, MobileTypeDisplayPolicy.resolve(1, false, state.copy(native = large)))
        assertEquals(special, MobileTypeDisplayPolicy.resolve(2, false, state.copy(native = special)))
    }

    @Test fun serviceLossDoesNotReusePreviousLabelAndReconnectUsesNewState() {
        assertEquals(large, MobileTypeDisplayPolicy.resolve(1, true, state))
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(1, true, state.copy(inService = false)))
        assertEquals(hidden, MobileTypeDisplayPolicy.resolve(1, true, state.copy(name = "")))
        assertEquals(large, MobileTypeDisplayPolicy.resolve(1, true, state.copy(name = "5G")))
    }

    @Test fun ownedValuesDoNotAccumulateAndPreserveNewHostWrites() {
        val value = MobileTypeOwnedValue<Int>()
        var current = 10
        repeat(100) { current = value.apply(current) { it + 3 } }
        assertEquals(13, current)
        assertEquals(26, value.apply(20) { it + 6 })
        assertEquals(20, value.restore(26))
        value.apply(20) { it + 2 }
        assertEquals(30, value.restore(30))
    }

    @Test fun nullableOwnedValuesRestoreNullAndCanBeReused() {
        val value = MobileTypeOwnedValue<String?>()
        assertEquals("bold", value.apply(null) { "bold" })
        assertNull(value.restore("bold"))
        assertEquals("native", value.restore("native"))
        assertEquals("host-new", value.apply("host-new") { it })
    }

    @Test fun reorderKeepsOtherIconsInOrderAndUsesPhysicalLeftInRtl() {
        val children = listOf("volte", "text", "signal", "activity")
        val after = listOf("volte", "signal", "text", "activity")
        assertEquals(children, MobileTypeDisplayPolicy.order(children, "text", "signal", true, false))
        assertEquals(after, MobileTypeDisplayPolicy.order(children, "text", "signal", false, false))
        assertEquals(after, MobileTypeDisplayPolicy.order(children, "text", "signal", true, true))
        assertEquals(children, MobileTypeDisplayPolicy.order(children, "text", "signal", false, true))
        assertEquals(children, MobileTypeDisplayPolicy.order(children, "missing", "signal", false, false))
    }

    @Test fun restoringOrderRetainsNewHostChildrenInsteadOfDroppingThem() {
        val state = MobileTypeOwnedValue<List<String>>()
        val original = listOf("text", "signal", "activity")
        val changed = state.apply(original) { MobileTypeDisplayPolicy.order(it, "text", "signal", false, false) }
        assertEquals(original, state.restore(changed))
        state.apply(original) { it.reversed() }
        val hostUpdate = original + "new-icon"
        assertEquals(hostUpdate, state.restore(hostUpdate))
    }
}
