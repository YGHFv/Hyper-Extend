/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object FiveGSettingsTargets {
    const val PACKAGE = "com.android.phone"
    const val ACTIVITY = "com.android.phone.settings.MiuiFiveGNetworkSetting"
    val start = SystemUiMethod("com.android.systemui.plugins.ActivityStarter", "startActivity", "void",
        listOf("android.content.Intent", "boolean", "com.android.systemui.animation.ActivityTransitionAnimator\$Controller",
            "boolean", "android.os.UserHandle"))
    val falsing = SystemUiMethod("com.android.systemui.plugins.FalsingManager", "isFalseLongTap", "boolean", listOf("int"))
    val fields = listOf(Triple(StockTilesTargets.FACTORY, "activityStarter", "dagger.Lazy"))
}
