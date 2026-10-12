/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object GmsTileTargets {
    const val TILE = "com.android.systemui.plugins.qs.QSTile"
    const val STATE = "$TILE\$State"
    const val BOOLEAN = "$TILE\$BooleanState"
    const val FACTORY = StockTilesTargets.FACTORY
    const val HOST = "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"
    val create = SystemUiMethod(FACTORY, "createTile", TILE, listOf("java.lang.String"))
    val callers = listOf(
        SystemUiMethod(FACTORY, "createTile", TILE, listOf("java.lang.String", "boolean")),
        SystemUiMethod("com.android.systemui.plugins.qs.QSFactory", "createTile", TILE, listOf("java.lang.String", "boolean")),
        SystemUiMethod("com.android.systemui.qs.panels.domain.interactor.TilesAvailabilityInteractor", "getUnavailableTiles",
            "java.lang.Object", listOf("java.lang.Iterable", "kotlin.coroutines.jvm.internal.ContinuationImpl")),
        SystemUiMethod("com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractorImpl", "createTileSync",
            TILE, listOf("com.android.systemui.qs.pipeline.shared.TileSpec")),
    )
    val pluginCallers = listOf(
        SystemUiMethod("miui.systemui.controlcenter.qs.QSController", "createTile", TILE, listOf("java.lang.String")),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelTilesDelegate", "getQSTile",
            TILE, listOf("java.lang.String")),
    )
    val userContext = SystemUiMethod(HOST, "getUserContext", "android.content.Context")
    val lazyGet = SystemUiMethod("dagger.Lazy", "get", "java.lang.Object")
    val falseTap = SystemUiMethod("com.android.systemui.plugins.FalsingManager", "isFalseTap", "boolean", listOf("int"))
    val copy = SystemUiMethod(BOOLEAN, "copy", STATE)
    val callback = SystemUiMethod("$TILE\$Callback", "onStateChanged", "void", listOf(STATE))
    val callbackType = SystemUiMethod("$TILE\$Callback", "getCallbackType", "int")
    val nextId = SystemUiMethod("com.android.internal.logging.InstanceIdSequence", "newInstanceId", "com.android.internal.logging.InstanceId")
    val methods = listOf(create, userContext, lazyGet, falseTap, copy, callback, callbackType, nextId) + callers
    val fields = listOf("qsHostLazy", "falsingManager").map { Triple(FACTORY, it, "dagger.Lazy") } +
        listOf("state" to "int", "spec" to "java.lang.String", "label" to "java.lang.CharSequence",
            "contentDescription" to "java.lang.CharSequence", "stateDescription" to "java.lang.CharSequence",
            "expandedAccessibilityClassName" to "java.lang.String", "handlesLongClick" to "boolean",
            "icon" to "$TILE\$Icon").map { (name, type) -> Triple(STATE, name, type) } +
        Triple("$TILE\$AdapterState", "value", "boolean")
}
