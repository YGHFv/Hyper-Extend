/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object ScreenshotCaptureTargets {
    private val parameters = listOf("android.content.Context", "int", "android.graphics.Rect", "[Ljava.lang.String;")
    val capture = SystemUiMethod("d1.d", "a", "android.graphics.Bitmap", parameters, false)
    val caller = SystemUiMethod("com.miui.screenshot.core.util.DisplayCapture",
        "captureDisplay", "android.graphics.Bitmap", parameters, true)
    private const val BUILDER = "android.window.ScreenCaptureInternal\$CaptureArgs\$Builder"
    val exclude = SystemUiMethod(BUILDER, "setExcludeOrIncludeLayerNames", BUILDER,
        listOf("[Ljava.lang.String;"), false)
    val mode = SystemUiMethod(BUILDER, "setMiCaptureMode", BUILDER, listOf("int"), false)
}
