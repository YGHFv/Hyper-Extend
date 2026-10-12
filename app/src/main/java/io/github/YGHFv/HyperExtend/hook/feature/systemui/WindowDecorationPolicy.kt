/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object WindowDecorationPolicy {
    fun hideBottom(enabled: Boolean, windowingMode: Int?) = enabled && windowingMode == 5
    fun infinityResult(original: Boolean, enabled: Boolean, gestureScope: Boolean) =
        original && !(enabled && gestureScope)
    fun directGestureCaller(frames: List<Pair<String, String>>): Boolean {
        val t = WindowDecorationTargets
        val first = frames.firstOrNull { (owner, name) ->
            owner.startsWith("com.android.wm.shell.") && !(owner == t.infinity.owner && name == t.infinity.name)
        }
        return first == (t.event.owner to t.event.name) || first == (t.findTask.owner to t.findTask.name)
    }
}

internal class WindowGestureScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun <T> within(block: () -> T): T {
        val previous = depth.get()
        depth.set((previous ?: 0) + 1)
        return try { block() } finally {
            if (previous == null) depth.remove() else depth.set(previous)
        }
    }
}
