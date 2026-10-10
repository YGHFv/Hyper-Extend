/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object VolumeLongPressPolicy {
    const val FEATURE = "volume_long_press_expand"
    const val DELAY = 300L
    fun eligible(enabled: Boolean, systemUi: Long?, plugin: Long?, mainDisplay: Boolean,
                 attached: Boolean, shown: Boolean, expanded: Boolean, animating: Boolean,
                 controlCenter: Boolean, touchExploration: Boolean, appPanel: Boolean,
                 activeSlider: Boolean, expandButtonTouch: Boolean): Boolean =
        enabled && systemUi == 202602260L && plugin == 183022200L && mainDisplay && attached && shown &&
            !expanded && !animating && !controlCenter && !touchExploration && !appPanel &&
            activeSlider && !expandButtonTouch
}

/** Observation never changes a drag. Only a fired hold owns the remainder after native CANCEL. */
internal class VolumeHold(val start: Long, private val x: Float, private val y: Float,
                          private val pointer: Int, private val slop: Float) {
    var cancelled = start < 0 || !x.isFinite() || !y.isFinite() || pointer < 0 || !slop.isFinite() || slop < 0
        private set
    var fired = false
        private set
    fun sample(time: Long, x: Float, y: Float, pointer: Int, count: Int) {
        if (time < start || !x.isFinite() || !y.isFinite() || pointer != this.pointer || count != 1 ||
            kotlin.math.hypot(x - this.x, y - this.y) > slop) cancel()
    }
    fun cancel() { cancelled = true }
    fun fire(now: Long): Boolean {
        if (cancelled || fired || now < start || now - start !in VolumeLongPressPolicy.DELAY..1000L) return false
        fired = true
        return true
    }
}
