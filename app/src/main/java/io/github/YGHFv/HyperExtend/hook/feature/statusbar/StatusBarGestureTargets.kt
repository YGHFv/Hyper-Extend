/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object StatusBarGestureTargets {
    const val OWNER = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    const val VIEW = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
    const val HANDLER = "com.android.systemui.statusbar.phone.MiuiStatusBarTouchHandler"
    const val DETECTOR = "com.android.systemui.statusbar.gesture.StatusBarLongPressGestureDetector"
    const val LISTENER = "$DETECTOR\$gestureDetector\$1"
    const val CLICK_TOOL = "com.miui.systemui.statusbar.StatusBarClickTool"
    const val COMMAND_QUEUE = "com.android.systemui.statusbar.notification.data.repository.DynamicIslandCommandQueueRepository"
    const val ISLAND = "com.android.systemui.statusbar.StatusBarIslandControllerImpl"
    const val FLOW = "kotlinx.coroutines.flow.ReadonlyStateFlow"
    val touch = SystemUiMethod(OWNER, "onTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val dispatch = SystemUiMethod(OWNER, "dispatchTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val sleep = SystemUiMethod("android.os.PowerManager", "goToSleep", "void", listOf("long"))
    val nativeDoubleTap = SystemUiMethod(LISTENER, "onDoubleTapEvent", "boolean", listOf("android.view.MotionEvent"))
    val nativeSingleTap = SystemUiMethod(LISTENER, "onSingleTapConfirmed", "boolean", listOf("android.view.MotionEvent"))
    val detectorTouch = SystemUiMethod("android.view.GestureDetector", "onTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val detectorMessage = SystemUiMethod("android.view.GestureDetector\$GestureHandler", "handleMessage", "void", listOf("android.os.Message"))
    val nativeClick = SystemUiMethod(CLICK_TOOL, "invokeInputManager", "void", listOf("boolean"))
    val stateValue = SystemUiMethod("kotlinx.coroutines.flow.StateFlow", "getValue", "java.lang.Object")
    val pairFirst = SystemUiMethod("kotlin.Pair", "getFirst", "java.lang.Object")
    val lazyGet = SystemUiMethod("dagger.Lazy", "get", "java.lang.Object")
    val inputInstance = SystemUiMethod("miui.hardware.input.MiuiInputManager", "getInstance",
        "miui.hardware.input.MiuiInputManager", isStatic = true)
    val scrollTop = SystemUiMethod("miui.hardware.input.MiuiInputManager", "scrollToTop", "void")
    val floatingWindow = SystemUiMethod("android.util.MiuiMultiWindowUtils", "startSmallFreeformForControlCenter",
        "java.lang.String", listOf("android.content.Context"), isStatic = true)
    val callers = listOf(
        SystemUiMethod("androidx.appcompat.widget.ForwardingListener", "onTouch", "boolean", listOf("android.view.View", "android.view.MotionEvent")),
        SystemUiMethod("androidx.appcompat.widget.ForwardingListener\$TriggerLongPress", "run", "void"),
        SystemUiMethod("androidx.constraintlayout.motion.widget.MotionLayout", "handlesTouchEvent", "boolean", listOf("float", "float", "android.view.View", "android.view.MotionEvent")),
        SystemUiMethod("com.android.systemui.globalactions.GlobalActionsDialogLite\$ActionsDialogLiteDelegate\$\$ExternalSyntheticLambda4",
            "onTouch", "boolean", listOf("android.view.View", "android.view.MotionEvent")),
    )
}
