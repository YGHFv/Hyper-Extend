/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object PercentageTargets {
    const val VOLUME = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController\$seekBarListener\$1"
    val fields = listOf(Triple(VOLUME, "this\$0", SliderValueTargets.VOLUME),
        Triple(SliderValueTargets.VOLUME, "streamMaxVolume", "int"), Triple(SliderValueTargets.VOLUME, "muted", "boolean"))
    val queries = listOf(SliderValueTargets.volumeLevel)
    val listeners = listOf(
        "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController\$seekBarListener\$1",
        "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate\$seekBarListener\$1",
        "miui.systemui.plugin.brightness.slider.BrightnessWindowSliderDelegate\$seekBarListener\$1", VOLUME)
    val methods = listeners.flatMap { owner -> listOf(
        SystemUiMethod(owner, "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod(owner, "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod(owner, "onStopTrackingTouch", "void", listOf("android.widget.SeekBar"))) }
    // Complete current plugin dispatch xrefs for the OnSeekBarChangeListener family.
    val callers = listOf(
        SystemUiMethod("com.android.systemui.MiPlaySeekBar", "performAccessibilityAction", "boolean", listOf("int", "android.os.Bundle")),
        SystemUiMethod("com.android.systemui.miui.widget.TimerSeekBar", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("com.android.systemui.miui.widget.TimerSeekBar", "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("com.android.systemui.miui.widget.TimerSeekBar", "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController\$foldStateCallback\$1", "onFoldStateChanged", "void", listOf("boolean")),
        SystemUiMethod("miui.systemui.controlcenter.widget.HorizontalSeekBar\$onAttachedToWindow\$1", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("miui.systemui.controlcenter.widget.HorizontalSeekBar\$onAttachedToWindow\$1", "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.controlcenter.widget.HorizontalSeekBar\$onAttachedToWindow\$1", "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.controlcenter.widget.VerticalSeekBar\$1", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("miui.systemui.controlcenter.widget.VerticalSeekBar", "dispatchTouchEvent", "boolean", listOf("android.view.MotionEvent")),
        SystemUiMethod("miui.systemui.controlcenter.widget.VerticalSeekBar", "startTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.controlcenter.widget.VerticalSeekBar", "stopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.plugin.brightness.slider.BrightnessWindowSliderDelegate\$seekBarListener\$1", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("miui.systemui.plugin.brightness.slider.BrightnessWindowSliderDelegate\$seekBarListener\$1", "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miui.systemui.plugin.brightness.slider.BrightnessWindowSliderDelegate\$seekBarListener\$1", "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miuix.androidbasewidget.widget.SeekBar\$2", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("miuix.androidbasewidget.widget.SeekBar\$2", "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miuix.androidbasewidget.widget.SeekBar\$2", "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miuix.miuixbasewidget.widget.HyperProgressSeekBar\$7", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        SystemUiMethod("miuix.miuixbasewidget.widget.HyperProgressSeekBar\$7", "onStartTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miuix.miuixbasewidget.widget.HyperProgressSeekBar\$7", "onStopTrackingTouch", "void", listOf("android.widget.SeekBar")),
        SystemUiMethod("miuix.miuixbasewidget.widget.HyperProgressSeekBar", "onTouchEvent", "boolean", listOf("android.view.MotionEvent")),
    )
}
