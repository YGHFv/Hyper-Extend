/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class DimTileIconPolicyTest {
    private fun eligible(enabled: Boolean = true, version: Long? = 202602260L,
                         spec: String? = "reduce_brightness", booleanState: Boolean = true,
                         state: Int = 1, hasIcon: Boolean = true, supplier: Boolean = false) =
        DimTileIconPolicy.eligible(enabled, version, spec, booleanState, state, hasIcon, supplier)

    @Test fun nativeOnOffAndUnavailableStatesCanKeepTheirOwnTint() {
        for (state in 0..2) assertTrue(eligible(state = state))
    }

    @Test fun disabledOrUnknownHostRemainsNative() {
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(version = null))
        assertFalse(eligible(version = 202602261L))
    }

    @Test fun onlyExactReduceBrightnessSpecIsEligible() {
        for (spec in listOf(null, "", "Reduce_brightness", "reduce_brightness ", "dark", "custom(pkg/.Tile)"))
            assertFalse(eligible(spec = spec))
    }

    @Test fun unexpectedStateAndSupplierRoutesAreNotTakenOver() {
        assertFalse(eligible(booleanState = false))
        assertFalse(eligible(state = -1))
        assertFalse(eligible(state = 3))
        assertFalse(eligible(hasIcon = false))
        assertFalse(eligible(supplier = true))
    }
}
