/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

/** 单击和双击共用确认时钟，不让原生延迟单击与模块自己的双击判定相互竞争。 */
internal class StatusBarTapTracker(
    private val doubleSlop: Float,
    private val touchSlop: Float,
    private val timeout: Long,
) {
    data class Trigger(val double: Boolean, val firstDown: Long, val downTime: Long)
    private data class Tap(val down: Long, val up: Long, val x: Float, val y: Float)
    private data class Press(val down: Long, val time: Long, val x: Float, val y: Float, val second: Boolean)
    private var pending: Tap? = null
    private var press: Press? = null

    val deadline: Long? get() = press?.let { it.time + timeout } ?: pending?.let { it.up + timeout }

    fun reset() { pending = null; press = null }

    fun down(time: Long, downTime: Long, x: Float, y: Float): Trigger? {
        if (press != null || !valid(time, downTime, x, y)) { reset(); return null }
        val first = pending
        if (first != null && time < first.up) { reset(); return null }
        val second = first != null && time - first.up in 0..timeout &&
            near(x, y, first.x, first.y, doubleSlop)
        val single = if (first != null && !second && time >= first.up)
            Trigger(false, first.down, first.down) else null
        if (!second) pending = null
        press = Press(downTime, time, x, y, second)
        return single
    }

    fun move(x: Float, y: Float) {
        val current = press ?: return
        if (!near(x, y, current.x, current.y, touchSlop)) reset()
    }

    fun up(time: Long, downTime: Long, x: Float, y: Float): Trigger? {
        val current = press ?: return null
        move(x, y)
        if (press == null || current.down != downTime || time - current.time !in 0..timeout) {
            reset(); return null
        }
        press = null
        val first = pending
        if (current.second && first != null) {
            reset()
            return Trigger(true, first.down, downTime)
        }
        pending = Tap(downTime, time, x, y)
        return null
    }

    fun expire(time: Long): Trigger? {
        val limit = deadline ?: return null
        if (time < limit) return null
        if (press != null) { reset(); return null }
        val first = pending ?: return null
        reset()
        return Trigger(false, first.down, first.down)
    }

    private fun valid(time: Long, downTime: Long, x: Float, y: Float): Boolean =
        time >= 0 && downTime in 0..time && x.isFinite() && y.isFinite()

    private fun near(x: Float, y: Float, otherX: Float, otherY: Float, slop: Float): Boolean {
        val dx = x - otherX
        val dy = y - otherY
        return dx.isFinite() && dy.isFinite() && dx * dx + dy * dy <= slop * slop
    }
}
