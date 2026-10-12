/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaPaletteTargets {
    const val SCHEME = "com.android.systemui.monet.ColorScheme"
    const val TONAL = "com.android.systemui.monet.TonalPalette"
    val shaderDraw = SystemUiMethod(MediaProgressTargets.BAR, "drawHighEndMode", "void", listOf("android.graphics.Canvas"))
    val constructorParameters = listOf("android.app.WallpaperColors", "boolean", "int")
    val methods = listOf(SystemUiMethod(SystemUiTargets.MEDIA_HOLDER, "getActionList", "java.util.List")) +
        listOf("setForegroundPrimaryColor", "setForegroundPrimaryDisableColor",
            "setBackgroundPrimaryColor", "setBackgroundPrimaryDisableColor")
            .map { SystemUiMethod(MediaProgressTargets.BAR, it, "void", listOf("int")) }
    val fields = listOf("mAccent1", "mAccent2", "mNeutral1", "mNeutral2").map { Triple(SCHEME, it, TONAL) } +
        listOf(Triple(TONAL, "allShades", "java.util.List")) +
        listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView")
            .map { Triple(SystemUiTargets.MEDIA_HOLDER, it, "android.widget.TextView") } +
        listOf(Triple(SystemUiTargets.MEDIA_HOLDER, "seamlessIcon", "android.widget.ImageView"),
            Triple(SystemUiTargets.MEDIA_HOLDER, "seekBar", MediaProgressTargets.BAR),
            Triple(MediaProgressTargets.BAR, "mDeviceLevel", "int"),
            Triple(MediaProgressTargets.BAR, "mPaint", "android.graphics.Paint"),
            Triple("com.android.systemui.media.controls.shared.model.MediaData", "isPlaying", "java.lang.Boolean")) +
        listOf("mForegroundPrimaryColor", "mForegroundPrimaryDisableColor",
            "mBackgroundPrimaryColor", "mBackgroundPrimaryDisableColor").map { Triple(MediaProgressTargets.BAR, it, "int") }
    val fallbackFields = listOf(
        Triple(SystemUiTargets.MEDIA_CONTROLLER, "appIconDrawable", "android.graphics.drawable.Drawable"),
        Triple("com.android.systemui.media.controls.shared.model.MediaData", "userId", "int"),
        Triple("com.android.systemui.media.controls.shared.model.MediaData", "appUid", "int"),
        Triple("com.android.systemui.media.controls.shared.model.MediaData", "packageName", "java.lang.String"))
}
