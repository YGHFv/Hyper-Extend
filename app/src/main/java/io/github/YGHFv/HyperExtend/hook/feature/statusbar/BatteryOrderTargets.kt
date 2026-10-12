/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object BatteryOrderTargets {
    const val OWNER = BatteryStyleTargets.OWNER
    val layout = SystemUiMethod(OWNER, "onLayout", "void",
        listOf("boolean", "int", "int", "int", "int"), false)
    val fields = listOf(
        "mBatteryDigitalView" to "android.widget.FrameLayout",
        "mBatteryPercentContainer" to "android.widget.LinearLayout",
        "mBatteryChargingView" to "android.widget.ImageView",
        "mBatteryStyle" to "int",
        "mLayoutFromTag" to "int",
        "mStatusBarState" to "int",
    ) + listOf("mAnimToAod", "mIsAodAnimate", "mToAod", "mIslandAnimate",
        "mIsIslandShowing", "mIsAddBatteryIsland", "mIsControlCenterExpanding").map { it to "boolean" }
}
