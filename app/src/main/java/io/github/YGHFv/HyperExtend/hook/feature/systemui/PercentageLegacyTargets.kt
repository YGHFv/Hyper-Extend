/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object PercentageLegacyTargets {
    const val CLASSIC = "com.android.systemui.settings.brightness.MiuiBrightnessSlider\$2"
    const val PANEL = "com.android.systemui.miui.volume.VolumePanelViewController"
    const val LISTENER = "$PANEL\$VolumeSeekBarChangeListener"
    const val COLUMN = "com.android.systemui.miui.volume.VolumeColumn"
    const val STATE = "com.android.systemui.plugins.VolumeDialogController\$StreamState"
    fun listener(owner: String) = listOf(
        SystemUiMethod(owner, "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod(owner, "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod(owner, "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")))
    val classic = listener(CLASSIC)
    val classicCallers = listOf(
        "com.android.systemui.common.ui.view.SeekBarWithIconButtonsView\$SeekBarChangeListener",
        "miuix.androidbasewidget.widget.SeekBar\$2",
        "miuix.miuixbasewidget.widget.HyperProgressSeekBar\$7",
    ).flatMap(::listener) + classic[1]
    val update = SystemUiMethod(PANEL, "updateVolumeColumnH", "void", listOf(COLUMN))
    val show = SystemUiMethod(PANEL, "showH", "void", listOf("int"))
    val dismiss = SystemUiMethod(PANEL, "dismissH", "void", listOf("int"))
    val active = SystemUiMethod(PANEL, "getActiveColumn", COLUMN)
    val state = SystemUiMethod(COLUMN, "getSs", STATE)
    val stream = SystemUiMethod(COLUMN, "getStream", "int")
    val tracking = SystemUiMethod(COLUMN, "getTracking", "boolean")
    val level = SystemUiMethod("miui.systemui.util.VolumeUtils", "progressToLevel", "int",
        listOf("int", "int"), isStatic = true)
    val volume = listener(LISTENER) + listOf(update, show, dismiss)
    val queries = listOf(active, state, stream, tracking, level)
    val fields = listOf(
        Triple(LISTENER, "mColumn", COLUMN), Triple(LISTENER, "this\$0", PANEL),
        Triple(PANEL, "mShowing", "boolean"), Triple(PANEL, "mActiveStream", "int"),
        Triple(COLUMN, "slider", "android.widget.SeekBar"),
        Triple(STATE, "level", "int"), Triple(STATE, "levelMax", "int"),
        Triple(STATE, "levelMin", "int"), Triple(STATE, "muted", "boolean"))
    val volumeCallers = listOf(
        SystemUiMethod("$PANEL\$12", "dismiss", "void", listOf("int")),
        SystemUiMethod("$PANEL\$1", "onDismissRequested", "void", listOf("int")),
        SystemUiMethod("$PANEL\$1", "onScreenOff", "void"),
        SystemUiMethod("$PANEL\$1", "onShowRequested", "void", listOf("int")),
        SystemUiMethod("$PANEL\$H", "handleMessage", "void", listOf("android.os.Message")),
        SystemUiMethod(PANEL, "c1", "void", listOf(PANEL, COLUMN), isStatic = true),
        SystemUiMethod(PANEL, "onConfigChanged", "void", listOf("int")),
        SystemUiMethod(PANEL, "onStateChangedH", "void", listOf("com.android.systemui.plugins.VolumeDialogController\$State")),
        SystemUiMethod(PANEL, "reCheckAllH", "void"),
        SystemUiMethod(PANEL, "recheckH", "void", listOf(COLUMN)),
        SystemUiMethod(PANEL, "setDisableVolumeDialog", "void", listOf("boolean")),
        SystemUiMethod(PANEL, "updateFoldState", "void", listOf("boolean")),
        SystemUiMethod(PANEL, "updateTempColumnH", "void", listOf("boolean", "boolean")),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.volume.VolumePanelDelegate", "onHidden", "void"),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.volume.VolumePanelDelegate", "prepareShow", "void",
            listOf("miui.systemui.controlcenter.panel.secondary.VolumePanelParams")),
    )
}
