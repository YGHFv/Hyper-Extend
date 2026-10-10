/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object SliderValueTargets {
    const val MAIN = "miui.systemui.controlcenter.panel.main."
    const val BRIGHTNESS = MAIN + "brightness.BrightnessSliderController"
    const val VOLUME = MAIN + "volume.VolumeSliderController"
    const val HOLDER = MAIN + "recyclerview.ToggleSliderViewHolder"
    const val SLIDER = "miui.systemui.controlcenter.widget.VerticalSeekBar"
    const val ROUTER = "miui.systemui.controlcenter.panel.SecondaryPanelRouter"
    val top = SystemUiMethod(HOLDER, "getTopText", "android.view.View")
    val slider = SystemUiMethod(HOLDER, "getSlider", SLIDER)
    val mirror = SystemUiMethod(HOLDER, "getInMirror", "boolean")
    val disabled = SystemUiMethod(HOLDER, "getDisableState", "boolean")
    val value = SystemUiMethod(SLIDER, "getValue", "int")
    val provider = SystemUiMethod("E0.a", "get", "java.lang.Object")
    val mainPanel = SystemUiMethod(ROUTER, "getInMainPanel", "boolean")
    val targetValue = SystemUiMethod(VOLUME, "getTargetValue", "int")
    val volumeLevel = SystemUiMethod(VOLUME, "valueToVolume", "int", listOf("int"))
    fun holder(owner: String) = SystemUiMethod(owner, "getSliderHolder", HOLDER)
    fun updates(owner: String) = listOf(
        SystemUiMethod(owner, "updateIconProgress", "void", if (owner == VOLUME) listOf("boolean", "boolean") else listOf("boolean")),
        SystemUiMethod(owner, "onBindViewHolder", "void"),
        SystemUiMethod(owner, "onConfigurationChanged", "void", listOf("int")),
    ) + if (owner == VOLUME) listOf(SystemUiMethod(owner, "updateSuperVolume", "void", listOf("boolean"))) else emptyList()
    fun cleanup(owner: String) = listOf("onUnbindViewHolder", "onDestroy").map { SystemUiMethod(owner, it, "void") }
    fun methods(owner: String) = listOf(top, slider, mirror, disabled, value, provider, mainPanel, holder(owner)) +
        updates(owner) + cleanup(owner) + if (owner == VOLUME) listOf(targetValue, volumeLevel) else emptyList()
    fun fields(owner: String) = listOf(Triple(owner, "secondaryPanelRouter", "E0.a")) +
        if (owner == VOLUME) listOf(Triple(owner, "streamMaxVolume", "int"), Triple(owner, "muted", "boolean"))
        else listOf(Triple(owner, "isInEditMode", "boolean"))

    // Deoptimize direct/default callers, not only the leaf interception targets.
    fun callers(owner: String): List<SystemUiMethod> {
        val common = listOf(
            SystemUiMethod(MAIN + "MainPanelContent", "onBindViewHolder", "void", listOf(MAIN + "recyclerview.MainPanelItemViewHolder", MAIN + "recyclerview.MainPanelListItem")),
            SystemUiMethod(MAIN + "MainPanelContent", "onUnbindViewHolder", "void", listOf(MAIN + "recyclerview.MainPanelItemViewHolder", MAIN + "recyclerview.MainPanelListItem")),
            SystemUiMethod(owner, "updateIconProgress\$default", "void", listOf(owner) +
                (if (owner == VOLUME) listOf("boolean", "boolean") else listOf("boolean")) + listOf("int", "java.lang.Object"), isStatic = true),
            SystemUiMethod(owner, "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode")),
        )
        return common + if (owner == VOLUME) listOf(
            SystemUiMethod(owner, "updateIcon", "void", listOf("boolean", "boolean", "boolean")),
            SystemUiMethod(owner, "handleUpdateSlider", "void", listOf("boolean", "boolean")),
            SystemUiMethod(owner, "access\$updateIconProgress", "void", listOf(owner, "boolean", "boolean"), isStatic = true),
            SystemUiMethod(owner, "updateSuperVolume\$default", "void", listOf(owner, "boolean", "int", "java.lang.Object"), isStatic = true),
        ) else listOf(
            SystemUiMethod(owner, "updateIcon", "void"),
            SystemUiMethod("$owner\$seekBarListener\$1", "onProgressChanged", "void", listOf("android.widget.SeekBar", "int", "boolean")),
        )
    }
}
