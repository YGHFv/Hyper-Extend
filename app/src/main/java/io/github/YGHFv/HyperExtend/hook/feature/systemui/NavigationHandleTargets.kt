/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NavigationHandleTargets {
    const val HANDLE = "com.android.systemui.navigationbar.gestural.NavigationHandle"
    const val PRESS = "com.android.systemui.navigationbar.gestural.NavigationHandlePressAnimator"
    val draw = SystemUiMethod(HANDLE, "onDraw", "void", listOf("android.graphics.Canvas"))
    val rect = SystemUiMethod(HANDLE, "getPillRect", "android.graphics.RectF")
    val radius = SystemUiMethod(HANDLE, "getPillRadius", "float")
    val color = SystemUiMethod(HANDLE, "getHandleColor", "int")
    val entries = listOf(draw, rect, radius, color)
    val callers = listOf(
        SystemUiMethod(HANDLE, "computePillGeometry", "void", listOf("boolean")),
        SystemUiMethod("com.android.systemui.navigationbar.HomeHandleVisibilityController", "getHomeHandleInfo",
            "com.android.systemui.navigationbar.HomeHandleInfo"),
    )
    val fields = mapOf("mRadius" to "float", "mLightColor" to "int", "mDarkColor" to "int",
        "mDarkIntensity" to "float", "mPaint" to "android.graphics.Paint",
        "mLastPillRect" to "android.graphics.RectF", "mLastPillRadius" to "float", "mPillGeometryDirty" to "boolean",
        "mBottom" to "float", "mHandleWidth" to "float", "mAdditionalHeightForAnimation" to "float",
        "mAdditionalWidthForAnimation" to "float", "mPulseAnimationProgress" to "float", "mPressAnimator" to PRESS)
    val pressFields = mapOf("mWidthScale" to "float", "mHeightScale" to "float")
}
