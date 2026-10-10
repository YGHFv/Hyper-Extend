/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object DimTileIconTargets {
    const val TILE = "com.android.systemui.qs.tiles.ReduceBrightColorsTile"
    const val BASE = "com.android.systemui.qs.tileimpl.QSTileImpl"
    const val STATE = "com.android.systemui.plugins.qs.QSTile\$State"
    const val BOOLEAN_STATE = "com.android.systemui.plugins.qs.QSTile\$BooleanState"
    const val ICON = "com.android.systemui.plugins.qs.QSTile\$Icon"
    const val DRAWABLE_ICON = "$BASE\$DrawableIcon"

    val update = SystemUiMethod(TILE, "handleUpdateState", "void", listOf(STATE, "java.lang.Object"))
    val refresh = SystemUiMethod(BASE, "handleRefreshState", "void", listOf("java.lang.Object"))
    val userSwitch = SystemUiMethod(BASE, "handleUserSwitch", "void", listOf("int"))
    val message = SystemUiMethod("$BASE\$H", "handleMessage", "void", listOf("android.os.Message"))
    val methods = listOf(update, refresh, userSwitch, message)
    val fields = listOf(
        Triple(BASE, "mContext", "android.content.Context"), Triple(BASE, "mTileSpec", "java.lang.String"),
        Triple(STATE, "icon", ICON), Triple(STATE, "iconSupplier", "java.util.function.Supplier"),
        Triple(STATE, "state", "int"),
    )
}
