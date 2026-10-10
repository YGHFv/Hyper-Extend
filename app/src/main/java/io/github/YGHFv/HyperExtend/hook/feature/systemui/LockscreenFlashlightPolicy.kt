/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/** An owned stream never becomes a native swipe, even after cancellation. */
internal class FlashlightPress {
    companion object {
        // Android ACTION_UP / ACTION_CANCEL. The outer host persists this return value.
        fun continues(action: Int): Boolean = action != 1 && action != 3
    }
    private var start: Long? = null
    private var x = 0f
    private var y = 0f
    private var pointer = -1
    private var cancelled = false
    val captured: Boolean get() = start != null

    fun down(time: Long, x: Float, y: Float, pointer: Int) {
        start = time; this.x = x; this.y = y; this.pointer = pointer; cancelled = false
    }
    fun move(x: Float, y: Float, pointer: Int, count: Int, slop: Float) {
        if (!x.isFinite() || !y.isFinite() || count != 1 || pointer != this.pointer ||
            !slop.isFinite() || slop < 0 || kotlin.math.hypot(x - this.x, y - this.y) > slop) cancelled = true
    }
    fun cancel() { cancelled = true }
    fun up(time: Long): Boolean {
        val began = start
        return (began != null && !cancelled && time >= began && time - began >= 400L).also { reset() }
    }
    fun reset() { start = null; cancelled = false; pointer = -1 }
}

/** Track identity, not equality: a newer host write is never restored over. */
internal class ShortcutOwnedValue<T> {
    private var owned = false
    private var baseline: T? = null
    private var last: T? = null
    fun apply(current: T?, replacement: T?): T? {
        if (!owned || current !== last) baseline = current
        last = replacement; owned = true
        return replacement
    }
    fun restore(current: T?): T? = (if (owned && current === last) baseline else current).also {
        owned = false; baseline = null; last = null
    }
}
