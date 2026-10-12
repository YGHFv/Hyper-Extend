/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object CardTileListTargets {
    const val CONTROLLER = "miui.systemui.controlcenter.qs.QSController"
    const val INITIALIZER = "$CONTROLLER\$cardStyleTileSpecs\$2"
    const val CARDS = "miui.systemui.controlcenter.panel.main.qs.QSCardsController"
    const val DETAIL = "miui.systemui.controlcenter.panel.secondary.detail.DetailPanelTilesDelegate"
    val initialize = SystemUiMethod(INITIALIZER, "invoke", "java.util.List")
    val detail = SystemUiMethod(DETAIL, "createTiles", "void")
    val create = SystemUiMethod(CONTROLLER, "createTile", "com.android.systemui.plugins.qs.QSTile", listOf("java.lang.String"))
    val context = SystemUiMethod("miui.systemui.util.ViewController", "getContext", "android.content.Context")
    val callers = listOf(
        SystemUiMethod(INITIALIZER, "invoke", "java.lang.Object"),
        SystemUiMethod("H0.j", "getValue", "java.lang.Object"),
        SystemUiMethod(CONTROLLER, "getCardStyleTileSpecs", "java.util.List"),
        SystemUiMethod(CONTROLLER, "getCreateStartExcludeTileSpecs", "java.util.ArrayList"),
        SystemUiMethod(CONTROLLER, "updateExcludeTileSpecs", "void"),
        SystemUiMethod(CARDS, "createCardTiles", "void"),
        SystemUiMethod(CARDS, "onCreate", "void"),
        SystemUiMethod(DETAIL, "onCreate", "void"),
    )
    val methods = listOf(initialize, detail, create, context) + callers
    val constructor = listOf("miui.systemui.controlcenter.windowview.ControlCenterWindowViewImpl",
        "android.os.Looper", "com.android.systemui.plugins.miui.qs.MiuiQSHost") + List(6) { "E0.a" }
    val fields = listOf(Triple(INITIALIZER, "this\$0", CONTROLLER))
}
