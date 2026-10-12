/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ClassicQsTargets {
    const val PANEL = "com.android.systemui.qs.MiuiQSPanel"
    const val QUICK = "com.android.systemui.qs.MiuiQuickQSPanel"
    const val TILE = "com.android.systemui.qs.MiuiTileLayout"
    const val PAGE = "com.android.systemui.qs.TilePage"
    const val PAGER = "com.android.systemui.qs.MiuiPagedTileLayout"
    val measure = SystemUiMethod(PAGER, "onMeasure", "void", listOf("int", "int"))
    val maxTiles = SystemUiMethod(QUICK, "setMaxTiles", "void", listOf("int"))
    val quickResources = SystemUiMethod(QUICK, "updateResources\$1", "void")
    val attached = SystemUiMethod(QUICK, "onAttachedToWindow", "void")
    val configuration = SystemUiMethod(PANEL, "onConfigurationChanged", "void", listOf("android.content.res.Configuration"))
    val updateColumns = SystemUiMethod(TILE, "updateColumns", "boolean")
    val createPage = SystemUiMethod(PAGER, "createTilePage", PAGE)
    val tileResources = SystemUiMethod(TILE, "updateResources", "boolean")
    val tileMaxColumns = SystemUiMethod(TILE, "setMaxColumns", "void", listOf("int"))
    val methods = listOf(measure, maxTiles, quickResources, configuration, attached,
        updateColumns, createPage, tileResources, tileMaxColumns,
        SystemUiMethod(TILE, "setMinRows", "boolean", listOf("int")),
        SystemUiMethod(PAGER, "setMaxColumns", "void", listOf("int")),
        SystemUiMethod(PAGER, "updateResources", "boolean"))
    val fields = listOf(
        Triple(PAGER, "mPages", "java.util.ArrayList"), Triple(PAGER, "mDistributeTiles", "boolean"),
        Triple(TILE, "mMaxAllowedRows", "int"), Triple(TILE, "mMinRows", "int"),
        Triple(TILE, "mResourceColumns", "int"), Triple(TILE, "mMaxColumns", "int"),
        Triple(TILE, "mColumns", "int"), Triple(TILE, "mCellMarginHorizontal", "int"),
    )
}
