/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object StatusBarGestureTargets {
    const val OWNER = "com.android.systemui.statusbar.phone.PhoneStatusBarView"
    const val VIEW = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
    const val HANDLER = "com.android.systemui.statusbar.phone.MiuiStatusBarTouchHandler"
    val touch = SystemUiMethod(OWNER, "onTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val dispatch = SystemUiMethod(OWNER, "dispatchTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val sleep = SystemUiMethod("android.os.PowerManager", "goToSleep", "void", listOf("long"))
    val callers = listOf(
        SystemUiMethod("androidx.appcompat.widget.ForwardingListener", "onTouch", "boolean", listOf("android.view.View", "android.view.MotionEvent")),
        SystemUiMethod("androidx.appcompat.widget.ForwardingListener\$TriggerLongPress", "run", "void"),
        SystemUiMethod("androidx.constraintlayout.motion.widget.MotionLayout", "handlesTouchEvent", "boolean", listOf("float", "float", "android.view.View", "android.view.MotionEvent")),
        SystemUiMethod("com.android.systemui.globalactions.GlobalActionsDialogLite\$ActionsDialogLiteDelegate\$\$ExternalSyntheticLambda4",
            "onTouch", "boolean", listOf("android.view.View", "android.view.MotionEvent")),
    )
}
