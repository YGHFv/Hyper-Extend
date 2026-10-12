/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object BatteryVisibilityTargets {
    val fields = BatteryStyleTargets.fields + listOf(
        "mBatteryIconView" to "com.android.systemui.statusbar.views.MiuiBatteryMeterIconView",
        "mHollowBatteryIconView" to "com.android.systemui.statusbar.views.MiuiHollowBatteryMeterIconView",
        "mBatteryChargingView" to "android.widget.ImageView",
        "mBatteryStyle" to "int",
    )
}
