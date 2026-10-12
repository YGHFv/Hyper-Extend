/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object NetworkSpeedTargets {
    val loopbackTx = SystemUiMethod("android.net.TrafficStats", "getTxBytes", "long", listOf("java.lang.String"), true)
    val loopbackRx = SystemUiMethod("android.net.TrafficStats", "getRxBytes", "long", listOf("java.lang.String"), true)
    val counters = listOf(loopbackTx, loopbackRx)
    val methods = listOf(
        SystemUiMethod("com.android.systemui.statusbar.phone.ui.IconManager", "addHolder", "com.android.systemui.statusbar.StatusIconDisplayable", listOf("int", "java.lang.String", "boolean", "com.android.systemui.statusbar.phone.StatusBarIconHolder"), false),
        SystemUiMethod("com.android.systemui.statusbar.phone.ui.IconManager", "onDensityOrFontScaleChanged", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl\$\$ExternalSyntheticLambda0", "accept", "void", listOf("java.lang.Object"), false),
        SystemUiMethod("com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl\$\$ExternalSyntheticLambda6", "accept", "void", listOf("java.lang.Object"), false),
        SystemUiMethod("com.android.systemui.statusbar.policy.NetworkSpeedController\$5", "handleMessage", "void", listOf("android.os.Message"), false),
        SystemUiMethod("com.android.systemui.statusbar.policy.NetworkSpeedController", "-\$\$Nest\$mupdateVisibility", "void", listOf("com.android.systemui.statusbar.policy.NetworkSpeedController", "java.lang.String"), true),
        SystemUiMethod("com.android.systemui.statusbar.policy.NetworkSpeedController", "updateText", "void", listOf("[Ljava.lang.String;"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.NetworkSpeedView", "onDensityOrFontScaleChanged", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.NetworkSpeedView", "onFinishInflate", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.views.NetworkSpeedView", "onMiuiThemeChanged", "void", listOf("boolean"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.NetworkSpeedView", "setNetworkSpeed", "void", listOf("java.lang.String", "java.lang.String"), false),
        SystemUiMethod("com.android.systemui.statusbar.views.NetworkSpeedView", "updateResources\$15", "void", listOf(), false),
    )
    val fields = listOf(
        Triple("com.android.systemui.statusbar.policy.NetworkSpeedController", "mContext", "android.content.Context"),
        Triple("com.android.systemui.statusbar.policy.NetworkSpeedController", "mBgHandler", "com.android.systemui.statusbar.policy.NetworkSpeedController\$5"),
        Triple("com.android.systemui.statusbar.policy.NetworkSpeedController", "mIsStatusBarHidden", "boolean"),
        Triple("com.android.systemui.statusbar.policy.NetworkSpeedController\$5", "this\$0", "com.android.systemui.statusbar.policy.NetworkSpeedController"),
        Triple("com.android.systemui.statusbar.views.NetworkSpeedView", "mNetworkSpeedNumberText", "android.widget.TextView"),
        Triple("com.android.systemui.statusbar.views.NetworkSpeedView", "mNetworkSpeedUnitText", "android.widget.TextView"),
    )
}
