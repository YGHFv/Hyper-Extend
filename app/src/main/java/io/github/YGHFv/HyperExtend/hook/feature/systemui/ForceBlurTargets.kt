/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ForceBlurTargets {
    const val OWNER = "com.android.systemui.statusbar.BlurUtils"
    val support = SystemUiMethod(OWNER, "supportsBlursOnWindows", "boolean")
    val apply = SystemUiMethod(OWNER, "applyBlur", "void", listOf("android.view.ViewRootImpl", "int"))
    val application = SystemUiMethod("android.app.ActivityThread", "currentApplication", "android.app.Application", isStatic = true)
    val rootView = SystemUiMethod("android.view.ViewRootImpl", "getView", "android.view.View")
    val cross = SystemUiMethod("android.view.CrossWindowBlurListeners", "isCrossWindowBlurEnabled", "boolean")
    val property = SystemUiMethod("android.os.SystemProperties", "getBoolean", "boolean", listOf("java.lang.String", "boolean"), true)
    val framework = listOf(application, rootView, cross, property)
    val callers = listOf(
        SystemUiMethod("com.android.keyguard.BaseMiuiKeyguardCameraViewInternal", "applyBlurRatio", "void", listOf("float"), false),
        SystemUiMethod("com.android.keyguard.panel.KeyguardMoveHelper", "updateKeyguardInfoBlurRatio", "void", listOf("float"), false),
        SystemUiMethod("com.android.systemui.dreams.DreamOverlayAnimationsController\$blurAnimator\$1\$1", "onAnimationUpdate", "void", listOf("android.animation.ValueAnimator"), false),
        SystemUiMethod("com.android.systemui.statusbar.BlurUtils", "applyBlur", "void", listOf("android.view.ViewRootImpl", "int"), false),
        SystemUiMethod("com.android.systemui.statusbar.BlurUtils", "dump", "void", listOf("java.io.PrintWriter", "[Ljava.lang.String;"), false),
        SystemUiMethod("com.android.systemui.statusbar.BlurUtils", "supportsBlursOnWindows", "boolean", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.NotificationShadeDepthController", "computeBlurAndZoomOut", "kotlin.Pair", listOf(), false),
        SystemUiMethod("com.android.systemui.statusbar.notification.modal.ModalWindowManager", "setBlurRatio", "void", listOf("float", "boolean"), false),
        SystemUiMethod("com.android.systemui.window.ui.DefaultBlurChoreographer\$newFrameCallback\$1", "doFrame", "void", listOf("long"), false),
        SystemUiMethod("com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenView", "removeQuickView", "void", listOf(), false),
        SystemUiMethod("com.miui.keyguard.biometrics.fod.MiuiGxzwQuickOpenView", "startShowQuickOpenItemAnimation", "void", listOf(), false),
        SystemUiMethod("com.miui.systemui.shade.blur.ShadeWindowBlurControllerImpl\$updateBlurRatioRunnable\$1", "run", "void", listOf(), false),
        SystemUiMethod("com.miui.systemui.statusbar.policy.BlurUtilsExt\$\$ExternalSyntheticLambda0", "invoke", "java.lang.Object", listOf("java.lang.Object"), false),
        SystemUiMethod("com.miui.systemui.util.WindowBlurUtilsImpl\$\$ExternalSyntheticLambda0", "invoke", "java.lang.Object", listOf("java.lang.Object"), false),
    ).distinct()
    const val CONSTRUCTOR_OWNER = "com.miui.systemui.statusbar.policy.BlurUtilsExt"
    val constructor = listOf("android.content.Context", "com.android.systemui.dump.DumpManager", "com.android.systemui.statusbar.policy.ConfigurationController", "android.view.CrossWindowBlurListeners", "com.android.systemui.statusbar.BlurUtils", "com.miui.interfaces.controlcenter.data.repository.MaterialStyleRepository", "com.android.systemui.util.concurrency.DelayableExecutor", "android.os.Handler", "com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor", "kotlinx.coroutines.CoroutineScope")
    val fields = listOf(
        Triple(OWNER, "crossWindowBlurListeners", "android.view.CrossWindowBlurListeners"),
        Triple(OWNER, "lastAppliedBlur", "int"),
        Triple(OWNER, "lastTargetViewRootImpl", "android.view.ViewRootImpl"),
        Triple("android.view.CrossWindowBlurListeners", "CROSS_WINDOW_BLUR_SUPPORTED", "boolean"),
    )
}
