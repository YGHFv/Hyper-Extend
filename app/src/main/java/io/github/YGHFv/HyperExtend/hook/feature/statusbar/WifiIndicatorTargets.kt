/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object WifiIndicatorTargets {
    const val BINDER = "com.android.systemui.statusbar.pipeline.wifi.ui.binder.MiuiWifiViewBinder"
    const val ACTIVITY = "$BINDER\$bind\$1\$1\$4\$1"
    const val STANDARD = "$BINDER\$bind\$1\$1\$5\$1"
    val bind = SystemUiMethod(BINDER, "bind", "$BINDER\$bind\$2", listOf(
        "android.view.ViewGroup",
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel"), true)
    val render = listOf(ACTIVITY, STANDARD).map {
        SystemUiMethod(it, "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"), false)
    }
    val callers = listOf(ACTIVITY, STANDARD).map {
        SystemUiMethod(it, "invoke", "java.lang.Object", listOf("java.lang.Object", "java.lang.Object"), false)
    } + SystemUiMethod(
        "com.android.systemui.statusbar.pipeline.wifi.ui.view.ModernStatusBarWifiView\$Companion\$\$ExternalSyntheticLambda0",
        "invoke", "java.lang.Object", emptyList(), false)
    val fields = listOf(
        Triple(ACTIVITY, "\$wifiActivity", "android.widget.ImageView"),
        Triple(STANDARD, "\$wifiStandard", "android.widget.TextView"),
    )
}
