/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.lang.ref.WeakReference

/** One attempt per source/bind; excluded surfaces defer work rather than retry every frame. */
internal class MediaArtworkRequestState {
    enum class Action { KEEP, CLEAR, PROCESS }
    private enum class Phase { EMPTY, DEFERRED, ATTEMPTED }
    private var phase = Phase.EMPTY
    private var source = WeakReference<Any>(null)

    fun invalidate() { phase = Phase.EMPTY; source.clear() }

    fun next(value: Any?, bound: Boolean, eligible: Boolean, force: Boolean = false): Action {
        val nextPhase = when {
            !bound || value == null -> Phase.EMPTY
            !eligible -> Phase.DEFERRED
            else -> Phase.ATTEMPTED
        }
        if (nextPhase != Phase.ATTEMPTED) {
            val changed = phase != nextPhase
            phase = nextPhase; source.clear()
            return if (changed || force) Action.CLEAR else Action.KEEP
        }
        if (!force && phase == Phase.ATTEMPTED && source.get() === value) return Action.KEEP
        phase = nextPhase; source = WeakReference(value)
        return Action.PROCESS
    }
}

/** Keeps at most two pixel frames, flattening an interrupted fade instead of nesting drawables. */
internal class MediaArtworkTransition(initial: IntArray) {
    var target = initial
        private set
    var previous: IntArray? = null
        private set
    private var started = 0L

    fun alpha(now: Long): Int = if (previous == null) 255 else
        ((now - started).coerceIn(0, DURATION_MS) * 255 / DURATION_MS).toInt()

    fun snapshot(now: Long): IntArray {
        val from = previous ?: return target
        val alpha = alpha(now)
        if (alpha == 255) return target
        if (alpha == 0) return from
        return IntArray(target.size) { i -> blend(from[i], target[i], alpha) }
    }

    fun update(next: IntArray, now: Long, animate: Boolean) {
        require(next.size == target.size)
        previous = if (animate) snapshot(now) else null
        target = next; started = now
    }

    fun finish() { previous = null }

    companion object {
        const val DURATION_MS = 333L
        private fun blend(from: Int, to: Int, alpha: Int): Int {
            fun channel(shift: Int): Int = (((from ushr shift and 255) * (255 - alpha) +
                (to ushr shift and 255) * alpha + 127) / 255) shl shift
            return 0xff000000.toInt() or channel(16) or channel(8) or channel(0)
        }
    }
}
