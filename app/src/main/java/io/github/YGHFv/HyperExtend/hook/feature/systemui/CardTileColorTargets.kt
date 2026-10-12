/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object CardTileColorTargets {
    const val VIEW = "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
    const val ICON = "miui.systemui.controlcenter.qs.tileview.QSCardItemIconView"
    const val STATE = TileCornerTargets.STATE
    const val BINDING = "miui.systemui.controlcenter.databinding.QsCardItemViewBinding"
    const val COMPANION = "miui.systemui.controlcenter.qs.tileview.QSItemView\$Companion"
    val enabled = SystemUiMethod(VIEW, "setEnabledBg", "void", listOf("android.graphics.drawable.Drawable"))
    val update = SystemUiMethod(VIEW, "updateState", "void", listOf(STATE, "boolean", "boolean"))
    val background = SystemUiMethod(VIEW, "updateBackground", "void", listOf("boolean", "boolean"))
    val resources = SystemUiMethod(VIEW, "updateResources", "void", listOf("boolean"))
    val material = SystemUiMethod(VIEW, "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode"))
    val recycle = SystemUiMethod(VIEW, "recycle", "void")
    val binding = SystemUiMethod(VIEW, "getBinding", BINDING)
    val blend = SystemUiMethod(VIEW, "getBackgroundBlendEnabled", "boolean")
    val restricted = SystemUiMethod(COMPANION, "isRestrictedCompat", "boolean", listOf(STATE))
    val warning = SystemUiMethod(COMPANION, "isTrafficWarning", "boolean", listOf(STATE))
    val icon = SystemUiMethod(ICON, "setIcon", "void", listOf(STATE, "boolean"))
    val refresh = listOf(update, background, resources, material, recycle)
    val callers = listOf(
        SystemUiMethod(VIEW, "updateDrawableBackground", "void", listOf("boolean")),
        SystemUiMethod(VIEW, "updateBackground\$default", "void", listOf(VIEW, "boolean", "boolean", "int", "java.lang.Object"), true),
        SystemUiMethod(VIEW, "updateResources\$default", "void", listOf(VIEW, "boolean", "int", "java.lang.Object"), true),
        SystemUiMethod(VIEW, "onConfigurationChanged", "void", listOf("int")),
        SystemUiMethod(VIEW, "onStateChanged", "void", listOf(STATE)),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.qs.QSRecord\$uiHandler\$1",
            "handleMessage", "void", listOf("android.os.Message")),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.qs.QSRecord", "onBindViewHolder", "void"),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.detail.DetailPanelTilesDelegate",
            "updateView", "void", listOf("com.android.systemui.plugins.qs.QSTile")),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.qs.QSCardViewHolder",
            "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode")),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.qs.QSItemViewHolder",
            "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode")),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.qs.QSItemViewHolder", "recycle", "void"),
    )
    val methods = listOf(enabled, binding, blend, restricted, warning, icon, TileCornerTargets.theme) + refresh + callers
    val fields = listOf(
        Triple(VIEW, "state", STATE), Triple(VIEW, "enabledBg", "android.graphics.drawable.Drawable"),
        Triple(ICON, "defaultIconColor", "int"), Triple(ICON, "miuiCellularTileIconColor", "int"),
        Triple(BINDING, "title", "miui.systemui.controlcenter.widget.FocusedTextView"),
        Triple(BINDING, "status", "miui.systemui.controlcenter.widget.FocusedTextView"),
        Triple(STATE, "state", "int"), Triple(STATE, "activeBgColor", "int"),
        Triple(STATE, "isTransient", "boolean"), Triple(STATE, "disabledByPolicy", "boolean"),
    )
}
