/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.MobileSignalSettings

internal data class MobileSignalPolicy(val mode: Int, val hideSim1: Boolean, val hideSim2: Boolean) {
    val enabled: Boolean get() = mode != 0 || hideSim1 || hideSim2

    /** null preserves the original Pair, including its distinct animation/visibility flags. */
    fun overrideVisibility(
        subId: Int,
        slot: Int?,
        defaultDataSubId: Int?,
        airplane: Boolean?,
        wifiConnected: Boolean?,
        dataConnected: Boolean?,
    ): Boolean? {
        if (MobileSignalSettings.allowsHiddenCards(mode) &&
            ((slot == 0 && hideSim1) || (slot == 1 && hideSim2))
        ) return false
        if (mode == 0) return null
        if (subId < 0 || (slot != null && slot < 0) || airplane == true) return false
        if (slot == null || airplane == null) return null
        return when (mode) {
            1 -> wifiConnected?.not()
            2, 3 -> when {
                defaultDataSubId == null -> null
                subId != defaultDataSubId -> false
                mode == 3 -> true
                else -> dataConnected
            }
            else -> null
        }
    }
}
