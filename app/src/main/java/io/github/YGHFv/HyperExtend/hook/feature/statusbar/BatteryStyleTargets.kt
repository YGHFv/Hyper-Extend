/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object BatteryStyleTargets {
    const val OWNER = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
    val refresh = listOf(
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onBatteryStyleChanged", "void", listOf("int"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "updateAll", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "updateChargeAndText", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onAttachedToWindow", "void", listOf(), false),
    )
    val callers = listOf(
        SystemUiMethod("com.android.systemui.statusbar.policy.MiuiBatteryControllerImpl\$4\$1", "run", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.policy.MiuiBatteryControllerImpl", "addCallback", "void", listOf("com.android.systemui.statusbar.policy.BatteryController\$BatteryStateChangeCallback"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onBatteryLevelChanged", "void", listOf("int", "boolean", "boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onChargeStateChanged", "void", listOf("boolean", "boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onDensityOrFontScaleChanged", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onFinishInflate", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onMiuiThemeChanged", "void", listOf("boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "onUiModeChanged", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "toggleAodMode", "void", listOf("boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "updateIslandChanged", "void", listOf("boolean", "boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "updateIslandShowing", "void", listOf("boolean", "boolean", "boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.MiuiBatteryMeterView", "updateState", "void", listOf("int"), false),
    )
    val fields = listOf(
        "mBatteryDigitalView" to "android.widget.FrameLayout",
        "mBatteryPercentContainer" to "android.widget.LinearLayout",
        "mBatteryPercentMarkView" to "android.widget.TextView",
        "mBatteryPercentView" to "android.widget.TextView",
    )
}
