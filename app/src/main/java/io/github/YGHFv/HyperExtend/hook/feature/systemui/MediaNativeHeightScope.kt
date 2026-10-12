/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/** Overrides only the synchronous native paint geometry, never measurement/touch bounds. */
internal class MediaNativeHeightScope {
    private class Frame(val receiver: Any, val height: Int)
    private val frame = ThreadLocal<Frame?>()
    fun height(receiver: Any?) = frame.get()?.takeIf { it.receiver === receiver }?.height
    fun <T> withHeight(receiver: Any, height: Int, block: () -> T): T {
        require(height > 0)
        val old = frame.get()
        frame.set(Frame(receiver, height))
        return try { block() } finally { if (old == null) frame.remove() else frame.set(old) }
    }
}
