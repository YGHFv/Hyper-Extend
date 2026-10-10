/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object StockTilesTargets {
    const val EDITOR = "com.android.systemui.qs.customize.MiuiQSCustomizerController"
    const val QUERY = "com.android.systemui.qs.customize.MiuiTileQueryHelper"
    const val FACTORY = "com.android.systemui.qs.tileimpl.MiuiQSFactory"
    const val PLUGIN_QUERY = "miui.systemui.controlcenter.qs.customize.TileQueryHelper"
    const val HOST = "com.android.systemui.plugins.miui.qs.MiuiQSHost"
    const val STOCK_RESOURCE = 0x7f140a06

    val show = SystemUiMethod(EDITOR, "show", "void", listOf("int", "int"))
    val classicCallers = listOf(
        SystemUiMethod("com.android.systemui.qs.MiuiQSPanel\$H", "handleMessage", "void", listOf("android.os.Message")),
        SystemUiMethod("$EDITOR\$restoreInstanceState\$1", "onLayoutChange", "void",
            listOf("android.view.View") + List(8) { "int" }),
    )
    val pluginStock = SystemUiMethod(PLUGIN_QUERY, "getTilesStock", "java.lang.String")
    val pluginAdd = SystemUiMethod(PLUGIN_QUERY, "addStockTiles", "void", listOf(HOST))
    val pluginCallers = listOf(
        SystemUiMethod(PLUGIN_QUERY, "queryTiles\$lambda\$2", "void", listOf(PLUGIN_QUERY, HOST), isStatic = true),
        SystemUiMethod(PLUGIN_QUERY, "b", "void", listOf(PLUGIN_QUERY, HOST), isStatic = true),
        SystemUiMethod("androidx.constraintlayout.motion.widget.a", "run", "void"),
    )
    val classicMethods = listOf(show) + classicCallers
    val pluginMethods = listOf(pluginStock, pluginAdd) + pluginCallers
    val providers = mapOf(
        "reduce_brightness" to "reduceBrightColorsTileProvider", "inversion" to "colorInversionTileProvider",
        "saver" to "dataSaverTileProvider", "dark" to "uiModeNightTileProvider",
        "onehanded" to "oneHandedModeTileProvider", "color_correction" to "colorCorrectionTileProvider",
    )
    val classicFields = listOf(
        Triple(EDITOR, "tileQueryHelper", QUERY), Triple(QUERY, "mContext", "android.content.Context"),
    )
    val pluginFields = listOf(Triple(PLUGIN_QUERY, "context", "android.content.Context"))
}
