/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object IslandMediaTargets {
    const val BASE = "com.android.systemui.statusbar.notification.mediaisland"
    const val BINDER = "$BASE.MiuiIslandMediaViewBinderImpl"
    const val HOLDER = "$BASE.MiuiIslandMediaViewHolder"
    const val CONTROLLER = "$BASE.MiuiIslandMediaControllerImpl"
    const val SET = "androidx.constraintlayout.widget.ConstraintSet"
    const val DATA = "com.android.systemui.media.controls.shared.model.MediaData"
    val attach = SystemUiMethod(BINDER, "attach", "void", listOf(HOLDER, HOLDER))
    val detach = SystemUiMethod(BINDER, "detach", "void")
    val info = SystemUiMethod(BINDER, "setInfoText", "void", listOf(HOLDER))
    val seamless = SystemUiMethod(BINDER, "setSeamless", "void", listOf(DATA, HOLDER))
    val actions = SystemUiMethod(BINDER, "setActionButtons", "void", listOf(DATA, HOLDER, "boolean"))
    val reset = SystemUiMethod(CONTROLLER, "resetPlayerHolder", "void")
    val load = SystemUiMethod(SET, "load", "void", listOf("android.content.Context", "int"))
    val clone = SystemUiMethod(SET, "clone", "void", listOf(SET))
    val edits = listOf("connect" to 4, "clear" to 2, "setMargin" to 3, "setGoneMargin" to 3,
        "setVisibility" to 2, "setHorizontalChainStyle" to 2).map { (name, count) ->
        SystemUiMethod(SET, name, "void", List(count) { "int" })
    }
    val callers = listOf(
        SystemUiMethod("$CONTROLLER\$mediaDataChangeListener\$1\$\$ExternalSyntheticLambda0", "invoke", "java.lang.Object"),
        SystemUiMethod(CONTROLLER, "reInflateView\$2", "void"),
        SystemUiMethod("$BINDER\$callback\$1", "onMetadataChanged", "void", listOf("android.media.MediaMetadata")),
        SystemUiMethod(BINDER, "bindMediaData", "void", listOf(DATA)),
        SystemUiMethod(BINDER, "onMediaDataChanged", "void", listOf("java.util.Map")),
    )
    val fields = listOf(
        Triple(BINDER, "holder", HOLDER), Triple(BINDER, "dummyHolder", HOLDER),
        Triple(BINDER, "mediaData", DATA),
        Triple(DATA, "semanticActions", "com.android.systemui.media.controls.shared.model.MediaButton"),
        Triple(CONTROLLER, "context", "android.content.Context"), Triple(CONTROLLER, "normalLayoutIsland", SET),
        Triple(CONTROLLER, "miuiPlayerHolder", HOLDER), Triple(CONTROLLER, "miuiDummyPlayerHolder", HOLDER),
        Triple(HOLDER, "player", "android.view.View"),
        Triple(HOLDER, "seamless", "android.view.ViewGroup"), Triple(HOLDER, "appIcon", "android.widget.ImageView"),
        Triple(HOLDER, "albumView", "android.view.View"),
    ) + listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView").map {
        Triple(HOLDER, it, "android.widget.TextView")
    } + (0..4).map { Triple(HOLDER, "action$it", "android.widget.ImageButton") }
    val methods = listOf(attach, detach, info, seamless, actions, reset, load, clone) + edits + callers
    val ids = mapOf("header_title" to 0x7f0b0534, "header_artist" to 0x7f0b0522, "icon" to 0x7f0b0566,
        "album_art" to 0x7f0b00c1, "media_seamless" to 0x7f0b071d, "actions" to 0x7f0b00ac) +
        (0..4).associate { "action$it" to 0x7f0b0057 + it }
}
