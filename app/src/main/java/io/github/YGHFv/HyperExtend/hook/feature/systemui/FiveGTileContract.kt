/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/** Full SystemUI 202602260 interface, including default methods intercepted by Java Proxy. */
internal object FiveGTileContract {
    val signatures = setOf(
        "addCallback(com.android.systemui.plugins.qs.QSTile\$Callback):void",
        "clearState():void",
        "click():void",
        "click(com.android.systemui.animation.Expandable):void",
        "destroy():void",
        "getCurrentTileUser():int",
        "getDetailAdapter():java.lang.Object",
        "getDetailsViewModel():com.android.systemui.plugins.qs.TileDetailsViewModel",
        "getDetailsViewModel(java.util.function.Consumer):boolean",
        "getInstanceId():com.android.internal.logging.InstanceId",
        "getMetricsCategory():int",
        "getMetricsSpec():java.lang.String",
        "getState():com.android.systemui.plugins.qs.QSTile\$State",
        "getTileLabel():java.lang.CharSequence",
        "getTileSpec():java.lang.String",
        "isAvailable():boolean",
        "isConnected():boolean",
        "isDestroyed():boolean",
        "isListening():boolean",
        "isTileReady():boolean",
        "longClick():void",
        "longClick(com.android.systemui.animation.Expandable):void",
        "populate(android.metrics.LogMaker):android.metrics.LogMaker",
        "refreshState():void",
        "removeCallback(com.android.systemui.plugins.qs.QSTile\$Callback):void",
        "removeCallbacks():void",
        "removeCallbacksByType(int):void",
        "secondaryClick():void",
        "secondaryClick(com.android.systemui.animation.Expandable):void",
        "setDetailListening(boolean):void",
        "setListening(java.lang.Object,boolean):void",
        "setTileSpec(java.lang.String):void",
        "showDetail(boolean):void",
        "userSwitch(int):void",
    )
    fun accepts(actual: List<String>): Boolean = actual.size == signatures.size && actual.toSet() == signatures
}
