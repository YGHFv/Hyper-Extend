/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenWallpaperTargets {
    const val PANEL = "com.android.keyguard.panel.KeyguardPanelViewController"
    const val EASE = "miuix.animation.utils.EaseManager\$EaseStyle"
    const val INTERPOLATE = "miuix.animation.utils.EaseManager\$InterpolateEaseStyle"
    const val FULL_AOD = "com.miui.interfaces.keyguard.IMiuiFullAodManager"
    const val SETTINGS = "com.miui.keyguard.KeyguardCommonSettingObserver"
    const val WALLPAPER = "com.android.keyguard.wallpaper.MiuiKeyguardWallPaperManager"
    val animation = SystemUiMethod(PANEL, "doWallpaperBlackAnim", "void", listOf("boolean", "float", "int",
        "boolean", "com.android.keyguard.clock.animation.AnimationTracker"))
    val config = SystemUiMethod("com.android.keyguard.clock.KeyguardClockContainer\$\$ExternalSyntheticOutline0",
        "m", "miuix.animation.base.AnimConfig", listOf(EASE), isStatic = true)
    val caller = SystemUiMethod(PANEL, "linkageViewAnim\$default", "void",
        listOf(PANEL, "boolean", "java.lang.String", "int"), isStatic = true)
    val fullAod = SystemUiMethod(FULL_AOD, "fullAodEnable", "boolean")
    val depth = SystemUiMethod(SETTINGS, "getDepthEffectEnable", "boolean")
    val video = SystemUiMethod(WALLPAPER, "isVideoWallPaper", "boolean")
    val videoDepth = SystemUiMethod(WALLPAPER, "isDepthVideoEnable", "boolean")
    val duration = SystemUiMethod(INTERPOLATE, "setDuration", INTERPOLATE, listOf("long"))
    val methods = listOf(animation, config, caller, fullAod, depth, video, videoDepth, duration)
    val fields = mapOf(
        "context" to "android.content.Context", "keyguardRootView" to "android.view.ViewGroup",
        "miuiFullAodManager" to FULL_AOD, "keyguardCommonSettingObserver" to SETTINGS,
        "miuiKeyguardWallPaperManager" to WALLPAPER, "localAodShowEaseStyle" to EASE,
        "localAodHideEaseStyle" to INTERPOLATE,
        *listOf("isDefaultTheme", "keyguardShowing", "isFlipFold", "keyguardOccluded", "sleepFromGone",
            "keyguardBouncerShowing", "isBouncerShowingWhenStartedGoingToSleep", "isGoingToDismissKeyguard",
            "isSuperSavePowerMode").map { it to "boolean" }.toTypedArray(),
    )
}
