/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.HeaderWeatherTargets
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object AlarmWindowTargets {
    const val VIEW = "com.android.systemui.statusbar.StatusBarIconView"
    const val CONTROLLER = "com.android.systemui.statusbar.policy.MiuiNextAlarmControllerImpl"
    val visible = SystemUiMethod(VIEW, "isIconVisible", "boolean", emptyList(), false)
    val capture = SystemUiMethod(CONTROLLER, "updateAlarmExistInBg", "void", emptyList(), false)
    val callers = listOf(
        SystemUiMethod("$CONTROLLER\$\$ExternalSyntheticLambda0", "run", "void", emptyList(), false),
        SystemUiMethod("$CONTROLLER\$1", "onPackageAdded", "void", listOf("java.lang.String", "int"), false),
        SystemUiMethod("$CONTROLLER\$1", "onPackageRemoved", "void", listOf("java.lang.String", "int"), false),
    ) + listOf("com.android.systemui.statusbar.phone.StatusIconContainer",
        "com.android.systemui.statusbar.views.MiuiStatusIconContainer").flatMap { owner ->
        listOf(SystemUiMethod(owner, "onLayout", "void", listOf("boolean", "int", "int", "int", "int"), false),
            SystemUiMethod(owner, "onMeasure", "void", listOf("int", "int"), false))
    }
    val user = HeaderWeatherTargets.user
    val userContext = HeaderWeatherTargets.userContext
    val fields = listOf(
        Triple(VIEW, "mSlot", "java.lang.String"),
        Triple(CONTROLLER, "mUTracker", "com.android.systemui.settings.UserTracker"),
    )
}
