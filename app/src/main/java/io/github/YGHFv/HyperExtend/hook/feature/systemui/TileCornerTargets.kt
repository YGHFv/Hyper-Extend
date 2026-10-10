/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TileCornerTargets {
    const val VIEW = "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView"
    const val STATE = "com.android.systemui.plugins.qs.QSTile\$State"
    const val THEME = "miui.systemui.util.ThemeUtils"
    val enabled = SystemUiMethod(VIEW, "setEnabledBg", "void", listOf("android.graphics.drawable.Drawable"))
    val disabled = SystemUiMethod(VIEW, "setDisabledBg", "void", listOf("android.graphics.drawable.Drawable"))
    val update = SystemUiMethod(VIEW, "updateIconInternal", "void", listOf(STATE, "boolean", "boolean", "boolean", "boolean"))
    val resources = SystemUiMethod(VIEW, "updateResources", "void")
    val corner = SystemUiMethod(VIEW, "setCornerRadius", "void", listOf("float"))
    val materialChanged = SystemUiMethod(VIEW, "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode"))
    val recycle = SystemUiMethod(VIEW, "recycle", "void")
    val activeBackground = SystemUiMethod(VIEW, "getActiveBackgroundDrawable", "android.graphics.drawable.Drawable", listOf(STATE))
    val restricted = SystemUiMethod(VIEW, "isRestrictedState", "boolean")
    val tint = SystemUiMethod(VIEW, "drawableTint", "void", listOf(STATE, "android.graphics.drawable.Drawable"))
    val theme = SystemUiMethod(THEME, "getDefaultPluginTheme", "boolean")
    val material = SystemUiMethod("miui.systemui.controlcenter.utils.ControlCenterUtils",
        "getBackgroundMaterialOpenedWithoutSuperPower", "boolean", listOf("android.content.Context"), isStatic = true)
    val callers = listOf(
        SystemUiMethod(VIEW, "liteIconUpdate", "void", listOf(STATE, "android.graphics.drawable.Drawable")),
        SystemUiMethod(VIEW, "getBackgroundDrawable", "android.graphics.drawable.Drawable", listOf(STATE)),
        SystemUiMethod(VIEW, "updateSize", "void"),
        SystemUiMethod(VIEW, "updateIcon", "void", listOf(STATE, "boolean", "boolean", "boolean", "boolean")),
        SystemUiMethod(VIEW, "updateIcon\$lambda\$3", "void", listOf(VIEW, "boolean", "boolean", "boolean"), isStatic = true),
        SystemUiMethod(VIEW, "updateIconInternal\$default", "void",
            listOf(VIEW, STATE, "boolean", "boolean", "boolean", "boolean", "int", "java.lang.Object"), isStatic = true),
        SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemView", "onConfigurationChanged", "void", listOf("int")),
        SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemView", "onMaterialModeChanged", "void", listOf("miui.systemui.util.MaterialMode")),
        SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemView", "recycle", "void"),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelTilesDelegate", "updateSize", "void", listOf("int")),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.detail.DetailPanelTilesDelegate", "onConfigurationChanged", "void", listOf("int")),
    )
    val refresh = listOf(update, resources, materialChanged, recycle)
    val methods = listOf(enabled, disabled) + refresh + listOf(theme, material, corner, activeBackground, restricted, tint) + callers
    val fields = listOf(
        Triple(VIEW, "card", "boolean"), Triple(VIEW, "isDetailTile", "boolean"), Triple(VIEW, "tileSize", "float"),
        Triple(VIEW, "enabledBg", "android.graphics.drawable.Drawable"),
        Triple(VIEW, "disabledBg", "android.graphics.drawable.Drawable"),
        Triple(VIEW, "icon", "miui.systemui.widget.ImageView"),
        Triple(VIEW, "state", STATE),
        Triple(VIEW, "defaultIconColor", "int"),
    )
    val stateFields = listOf("state" to "int", "activeBgColor" to "int", "disabledByPolicy" to "boolean", "isTransient" to "boolean", "spec" to "java.lang.String")
}
