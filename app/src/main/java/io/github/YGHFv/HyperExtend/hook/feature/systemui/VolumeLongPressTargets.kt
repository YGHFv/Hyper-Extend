/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object VolumeLongPressTargets {
    const val PREFIX = "com.android.systemui.miui.volume."
    const val SEEK = PREFIX + "MiuiVolumeSeekBar"
    const val DIALOG = PREFIX + "MiuiVolumeDialogView"
    const val MOTION = PREFIX + "MiuiVolumeDialogMotion"
    const val LISTENER = PREFIX + "h"
    val dispatch = SystemUiMethod(SEEK, "dispatchTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val dialogDispatch = SystemUiMethod(DIALOG, "dispatchTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val lifecycle = listOf(
        SystemUiMethod(DIALOG, "dismissH", "void", listOf("boolean", "java.lang.Runnable")),
        SystemUiMethod(DIALOG, "destroy", "void"),
        SystemUiMethod(DIALOG, "showH", "void", listOf("java.lang.Runnable")),
        SystemUiMethod(DIALOG, "onConfigurationChanged", "void", listOf("android.content.res.Configuration")),
        SystemUiMethod(DIALOG, "onExpandStateUpdated", "void", listOf("boolean")),
    )
    val animating = SystemUiMethod(DIALOG, "isAnimating", "boolean")
    val showing = SystemUiMethod(DIALOG, "isShowAnimating", "boolean")
    val expanded = SystemUiMethod(PREFIX + "widget.ExpandCollapseLinearLayout", "isExpanded", "boolean")
    val columnTouch = SystemUiMethod(PREFIX + "VolumeColumn\$onTouchListener\$2\$1", "onTouch", "boolean",
        listOf("android.view.View", "android.view.MotionEvent"))
    val callers = listOf(
        SystemUiMethod(PREFIX + "VolumePanelViewController", "dismissVolumePanel", "void", listOf("int")),
        SystemUiMethod(PREFIX + "VolumePanelViewController", "showVolumePanelH", "void", listOf("int")),
        SystemUiMethod(PREFIX + "VolumePanelViewController", "destroy", "void"),
        SystemUiMethod(PREFIX + "widget.ExpandCollapseStateHelper", "updateExpanded", "void", listOf("boolean", "boolean")),
    )
    val methods = listOf(dispatch, dialogDispatch, animating, showing, expanded, columnTouch) + lifecycle + callers
    val fields = listOf(
        Triple(SEEK, "mSeekBarOnclickListener", "$SEEK\$SeekBarOnclickListener"),
        Triple(LISTENER, "a", "java.lang.Object"),
        Triple(MOTION, "mVolumeSeekBar", SEEK), Triple(MOTION, "mVolumeView", "android.view.View"),
        Triple(MOTION, "mExpanded", "boolean"), Triple(MOTION, "mIsExpandButton", "boolean"),
        Triple(DIALOG, "mMotion", MOTION), Triple(DIALOG, "mNeedShowDialog", "boolean"),
        Triple(DIALOG, "isControlCenterPanel", "boolean"),
        Triple(DIALOG, "expandListener", "android.view.View\$OnClickListener"),
        Triple(DIALOG, "mExpandButton", "android.view.View"),
        Triple(DIALOG, "mCallback", "$MOTION\$Callback"),
    )
}
