/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TileMaterialPolicy {
    fun eligible(enabled: Boolean, blocked: Boolean, host: Long, plugin: Long, display: Int?,
        classic: Boolean, material: Boolean, theme: Boolean, normal: Boolean,
        state: Int, activeColor: Int, restricted: Boolean, transient: Boolean): Boolean =
        enabled && !blocked && host == 202602260L && plugin == 183022200L && display == 0 &&
            classic && material && theme && normal && state in 1..2 && activeColor == 0 &&
            !restricted && !transient

    fun resource(legacy: IntArray): Pair<IntArray, IntArray>? {
        if (legacy.size !in setOf(4, 6)) return null
        val modes = IntArray(legacy.size / 2) { legacy[it * 2 + 1] }
        if (modes[0] != 100 || modes[1] != 106 || modes.size == 3 && modes[2] != 3) return null
        return IntArray(modes.size) { legacy[it * 2] } to modes
    }

    fun changes(colors: IntArray?, modes: IntArray?, replacement: Pair<IntArray, IntArray>): Boolean =
        colors != null && modes != null && colors.size == modes.size &&
            colors.size == replacement.first.size && modes.contentEquals(replacement.second) &&
            !colors.contentEquals(replacement.first)

    fun restore(owned: Boolean, blocked: Boolean, classic: Boolean, material: Boolean): Boolean =
        owned && !blocked && classic && material
}

/** Always restore the immediate parent, including nested calls for another tile. */
internal class TileMaterialScope<T> {
    private val local = ThreadLocal<T>()
    val current: T? get() = local.get()
    fun <R> within(value: T, block: () -> R): R {
        val previous = local.get()
        local.set(value)
        return try { block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
