/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.HookSettings

internal data class BatteryVisibilityPolicy(
    val hideIcon: Boolean = false,
    val hidePercent: Boolean = false,
    val hideMark: Boolean = false,
    val hideCharging: Boolean = false,
) {
    val hidesAnything get() = hideIcon || hidePercent || hideMark || hideCharging
    val hidesMark get() = hidePercent || hideMark

    fun hiddenViewFields(batteryStyle: Int?): List<String> = buildList {
        if (hideIcon) {
            add("mBatteryIconView")
            add("mHollowBatteryIconView")
            // Retain the existing digital-container rule for older host layouts.
            if (batteryStyle == 1) add("mBatteryDigitalView")
        }
        if (hideCharging) add("mBatteryChargingView")
    }

    fun hiddenTextFields(): List<String> = buildList {
        if (hidePercent) {
            add("mBatteryPercentView")
            add("mBatteryTextDigitView")
        }
        if (hidesMark) add("mBatteryPercentMarkView")
    }

    fun percentSizeDp(storedSize: Int): Float? = textSizeDp(hidePercent, storedSize)
    fun markSizeDp(storedSize: Int): Float? = textSizeDp(hidesMark, storedSize)

    // Both after-hooks must agree: hiding wins regardless of callback order.
    private fun textSizeDp(hidden: Boolean, storedSize: Int): Float? =
        if (hidden) 0f else (storedSize * 0.5f).takeIf { it > 7.5f }

    companion object {
        fun from(settings: HookSettings) = BatteryVisibilityPolicy(
            hideIcon = settings.isOn("status_bar_icons.battery_icon"),
            hidePercent = settings.isOn("status_bar_icons.battery_percent"),
            hideMark = settings.isOn("status_bar_icons.battery_percent_mark"),
            hideCharging = settings.isOn("status_bar_icons.battery_charging"),
        )
    }
}
