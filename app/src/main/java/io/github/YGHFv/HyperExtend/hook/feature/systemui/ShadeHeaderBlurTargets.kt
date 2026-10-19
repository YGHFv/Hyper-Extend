/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ShadeHeaderBlurTargets {
    const val FEATURE = "shade_header_gradient_blur"
    const val OWNER = "com.miui.systemui.shade.header.ShadeHeaderClipController"
    const val BLUR = "com.miui.systemui.util.MiBlurCompat"
    val query = SystemUiMethod(OWNER, "getUseGradientBlur", "boolean")
    val callers = listOf(
        SystemUiMethod("$OWNER\$clipRatio\$1", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("$OWNER\$gradientBlurParams\$1", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("$OWNER\$gradientForegroundAlpha\$1", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("$OWNER\$start\$3\$1\$1", "emit", "java.lang.Object",
            listOf("java.lang.Object", "kotlin.coroutines.Continuation")),
        SystemUiMethod(OWNER, "access\$adjustPaintAlpha", "void", listOf(OWNER, "float", "int"), true),
        SystemUiMethod(OWNER, "drawChild", "boolean",
            listOf("com.miui.interfaces.shade.DrawChildParent", "android.graphics.Canvas", "android.view.View", "long")),
        SystemUiMethod(OWNER, "invalidateContainers", "void", listOf("boolean")),
    )
}

internal object ShadeHeaderBlurPolicy {
    fun useGradient(native: Boolean, enabled: Boolean, display: Int?, supported: Boolean) =
        native || (enabled && display == 0 && supported)
}
