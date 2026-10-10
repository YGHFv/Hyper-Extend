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
    val methods = listOf(measure, maxTiles, quickResources, configuration, attached)
    val fields = listOf(
        Triple(PAGER, "mPages", "java.util.ArrayList"), Triple(PAGER, "mDistributeTiles", "boolean"),
        Triple(TILE, "mMaxAllowedRows", "int"), Triple(TILE, "mMinRows", "int"),
    )
}
