/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object IslandBackgroundTargets {
    const val BINDER = IslandMediaTargets.BINDER
    const val HOLDER = IslandMediaTargets.HOLDER
    const val DATA = IslandMediaTargets.DATA
    const val BG = "com.mi.widget.view.MusicBgView"
    const val SHADER = "com.mi.widget.shader.MusicBgShader"
    val bind = SystemUiMethod(BINDER, "bindMediaData", "void", listOf(DATA))
    val foreground = SystemUiMethod(BINDER, "updateForegroundColors", "void", listOf(HOLDER))
    val music = SystemUiMethod(BINDER, "setMusicBgShader", "void", listOf(DATA, "android.graphics.drawable.Drawable"))
    val radius = SystemUiMethod(SHADER, "getBgCornerRadius", "float")
    val uiMode = SystemUiMethod("${IslandMediaTargets.CONTROLLER}\$configurationCallback\$1", "onUiModeChanged", "void")
    val callers = IslandMediaTargets.callers + listOf(uiMode, IslandMediaTargets.attach, bind, MediaProgressTargets.draw)
    val methods = listOf(IslandMediaTargets.attach, IslandMediaTargets.detach, bind, foreground, music, radius) +
        callers + listOf(MediaPaletteTargets.shaderDraw) + MediaPaletteTargets.methods.filter { it.owner == MediaProgressTargets.BAR }
    val fields = listOf(
        Triple(BINDER, "holder", HOLDER), Triple(BINDER, "dummyHolder", HOLDER),
        Triple(BINDER, "mediaData", DATA), Triple(DATA, "isPlaying", "java.lang.Boolean"),
        Triple(BINDER, "artWorkDrawable", "android.graphics.drawable.Drawable"),
        Triple(BINDER, "appIconDrawable", "android.graphics.drawable.Drawable"),
        Triple(HOLDER, "player", "android.view.View"), Triple(HOLDER, "mediaBgView", BG),
        Triple(BG, "mShader", SHADER), Triple(SHADER, "view", "android.view.View"),
        Triple(HOLDER, "seamlessIcon", "android.widget.ImageView"),
        Triple(HOLDER, "seekBar", MediaProgressTargets.BAR),
    ) + listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView").map {
        Triple(HOLDER, it, "android.widget.TextView")
    } + (0..4).map { Triple(HOLDER, "action$it", "android.widget.ImageButton") } +
        MediaPaletteTargets.fields.filter { it.first == MediaProgressTargets.BAR }
}
