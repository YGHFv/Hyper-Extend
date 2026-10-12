/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object IslandProgressTargets {
    const val STATE = "com.android.systemui.statusbar.notification.mediacontrol.MiuiSeekBarRenderState"
    val render = SystemUiMethod(IslandMediaTargets.BINDER, "onRenderState", "void", listOf(STATE))
    val seek = SystemUiMethod(IslandMediaTargets.BINDER, "seekBarChanged", "void",
        listOf(STATE, IslandMediaTargets.HOLDER, "boolean"))
    val methods = listOf(IslandMediaTargets.attach, IslandMediaTargets.detach, MediaProgressTargets.draw,
        MediaProgressTargets.changed, render, seek) + IslandMediaTargets.callers.take(2) + MediaProgressTargets.observerCallers
    val fields = listOf(
        Triple(IslandMediaTargets.BINDER, "holder", IslandMediaTargets.HOLDER),
        Triple(IslandMediaTargets.BINDER, "dummyHolder", IslandMediaTargets.HOLDER),
        Triple(IslandMediaTargets.BINDER, "seekBarProgressOwner", MediaProgressTargets.OWNER),
        Triple(IslandMediaTargets.HOLDER, "seekBar", MediaProgressTargets.BAR),
        Triple(IslandMediaTargets.HOLDER, "elapsedTimeView", "android.widget.TextView"),
    ) + MediaProgressTargets.fields.filter {
        it.first in setOf(MediaProgressTargets.BAR, MediaProgressTargets.PROGRESS, MediaProgressTargets.OBSERVER)
    }
}
