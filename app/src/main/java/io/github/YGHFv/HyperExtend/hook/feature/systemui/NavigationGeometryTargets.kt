/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NavigationGeometryTargets {
    const val BAR = "com.android.systemui.navigationbar.views.NavigationBar"
    const val VIEW = "com.android.systemui.navigationbar.views.NavigationBarView"
    const val EDGE = "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler"
    val params = SystemUiMethod(BAR, "getBarLayoutParamsForRotation", "android.view.WindowManager\$LayoutParams", listOf("int"))
    val height = SystemUiMethod(VIEW, "getNavBarHeight", "int")
    val measure = SystemUiMethod(VIEW, "onMeasure", "void", listOf("int", "int"))
    val callers = listOf(
        SystemUiMethod(BAR, "onInit", "void"),
        SystemUiMethod(BAR, "updateViewLayout", "void", listOf("int")),
        SystemUiMethod("$BAR\$9", "getSampledRegion", "android.graphics.Rect", listOf("android.view.View")),
    )
    val methods = listOf(params, height, measure) + callers
    val fields = listOf(
        Triple(BAR, "mContext", "android.content.Context"),
        Triple(BAR, "mNavBarMode", "int"), Triple(VIEW, "mNavBarMode", "int"),
        Triple(BAR, "mEdgeBackGestureHandler", EDGE), Triple(VIEW, "mEdgeBackGestureHandler", EDGE),
        Triple(EDGE, "mIsButtonForcedVisible", "boolean"),
    )
    val resources = mapOf(
        0x10502a4 to "navigation_bar_frame_height",
        0x10502a8 to "navigation_bar_height",
        0x10502aa to "navigation_bar_height_landscape",
    )
}
