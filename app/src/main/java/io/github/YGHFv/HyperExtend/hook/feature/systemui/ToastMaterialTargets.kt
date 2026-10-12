/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ToastMaterialTargets {
    const val FEATURE = io.github.YGHFv.HyperExtend.core.ToastMaterialSettings.FEATURE
    const val TOAST = "com.android.systemui.toast.SystemUIToast"
    const val VIEW = "com.miui.systemui.widget.ToastLinearLayout"
    const val TEXT_ID = 0x7f0b0c13
    val constructor = listOf("android.view.LayoutInflater", "android.content.Context", "android.content.Context",
        "java.lang.CharSequence", "com.android.systemui.plugins.ToastPlugin\$Toast", "java.lang.String", "int", "int")
    val create = SystemUiMethod("com.android.systemui.toast.ToastFactory", "createToast", TOAST,
        listOf("android.content.Context", "android.content.Context", "java.lang.CharSequence", "java.lang.String", "int", "int"))
    val nativeBlur = SystemUiMethod("com.android.systemui.toast.ToastInjector", "useBlurRes", "boolean",
        listOf("android.view.View"), isStatic = true)
    val callers = listOf(
        SystemUiMethod("com.android.systemui.brightness.ui.compose.BrightnessSliderKt\$\$ExternalSyntheticLambda6", "invoke", "java.lang.Object"),
        SystemUiMethod("com.android.systemui.qs.tiles.dialog.InternetDetailsContentController", "makeOverlayToast", "void", listOf("int")),
        SystemUiMethod("com.android.systemui.toast.ToastUI\$\$ExternalSyntheticLambda0", "run", "void"))
    val fields = listOf(Triple(TOAST, "mToastView", "android.view.View"))
}
