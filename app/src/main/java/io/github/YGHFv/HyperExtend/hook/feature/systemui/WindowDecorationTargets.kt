/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object WindowDecorationTargets {
    const val JAR = "/system_ext/framework/Miui-WindowManager-Shell.jar"
    const val SHA256 = "aae1827abff92240c405a77794506bebbb801b06a57c7f66e501ec181af42ded"
    private const val DECOR = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor."
    private const val FREEFORM = "com.android.wm.shell.multitasking.miuifreeform."
    private const val TASK = "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo"
    const val BASE = DECOR + "decoration.MiuiDecorationBase"
    const val ROOT_HOST = DECOR + "decoration.MiuiDecorationRootViewHost"
    val dotDraw = SystemUiMethod(DECOR + "decoration.MiuiDecorationDotView", "onDraw", "void", listOf("android.graphics.Canvas"))
    val menu = SystemUiMethod(DECOR + "decoration.MiuiDecorationDot", "createHandleMenu", "void", listOf("int", "java.lang.String"))
    val menuCaller = menu.copy(name = "handleCaptionClicked", parameters = emptyList())
    val infinity = SystemUiMethod("com.android.wm.shell.multitasking.stubs.infinitymode.MiuiInfinityModeStatus", "isEnabled", "boolean", isStatic = true)
    val event = SystemUiMethod(DECOR + "MulWinSwitchEventHandler", "handleReceivedMotionEvent", "void", listOf("android.view.MotionEvent", "android.view.InputMonitor"))
    val findTask = SystemUiMethod(FREEFORM + "MiuiFreeformModeGestureHandler", "findActiveMiuiFreeformTask", TASK, listOf("float", "float"))
    val freeformHeight = SystemUiMethod(DECOR + "decoration.MiuiDecorationInfo", "getFreeformBottomCaptionHeight", "int", isStatic = true)
    val bottomHeight = SystemUiMethod(DECOR + "decoration.MiuiDecorationBottom", "getBottomCaptionHeight", "int")
    val bottomDraw = dotDraw.copy(owner = DECOR + "decoration.MiuiDecorationBottomView")
    val rootView = SystemUiMethod(ROOT_HOST, "getDecorationRootView", DECOR + "decoration.MiuiDecorationRootView")
    val fields = listOf(Triple(BASE, "mWindowingMode", "int"), Triple(BASE, "mMiuiDecorationRootViewHost", ROOT_HOST))
    val gestureCallers = listOf(
        event.copy(name = "lambda\$sendCancelEvent\$1", parameters = emptyList()),
        event.copy(name = "onEvent", returns = "boolean"),
        SystemUiMethod(findTask.owner, "handleMotionEvents", FREEFORM + "MiuiFreeformModeTouchResult", event.parameters),
    )
    val bottomCallers = listOf(
        SystemUiMethod(bottomHeight.owner, "updateCaptionGeometry", "void", listOf(BASE + "\$RelayoutResult")),
        SystemUiMethod(bottomHeight.owner, "updateCornerRadius", "void", listOf("android.view.SurfaceControl\$Transaction", "float", "boolean")),
        SystemUiMethod(bottomHeight.owner, "isTouchedInSplitBottomCaption", "boolean", listOf("android.view.MotionEvent")),
        SystemUiMethod(bottomHeight.owner, "updateBottomCaptionSurface", "void", listOf("android.view.SurfaceControl\$Transaction", "android.graphics.Rect", "float", "float", "boolean")),
        SystemUiMethod(DECOR + "decoration.MiuiDecorationHomeBottom", "updateCaptionContainerSurface", "void", listOf(BASE + "\$RelayoutResult", "android.view.SurfaceControl\$Transaction")),
    )
    val methods = listOf(dotDraw, menu, menuCaller, infinity, event, findTask, freeformHeight, bottomHeight, bottomDraw, rootView) + gestureCallers + bottomCallers
}
