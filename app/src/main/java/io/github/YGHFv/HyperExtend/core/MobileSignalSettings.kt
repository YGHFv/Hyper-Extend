/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

object MobileSignalSettings {
    const val MODE = "status_bar_mobile.signal_mode"
    const val HIDE_SIM_1 = "status_bar_mobile.hide_sim_1"
    const val HIDE_SIM_2 = "status_bar_mobile.hide_sim_2"

    fun mode(value: String?): Int = value?.toIntOrNull()?.takeIf { it in 0..3 } ?: 0

    // Data-SIM modes own card selection, matching HyperCeiler's disabled switches.
    fun allowsHiddenCards(mode: Int): Boolean = mode in 0..1

    fun isHideCardOption(key: String): Boolean = key == HIDE_SIM_1 || key == HIDE_SIM_2
}
