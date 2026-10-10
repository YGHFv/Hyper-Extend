/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * MobileTypeSingle2Hook modes, with explicit unknown/no-service fallback.
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object MobileTypeDisplayPolicy {
    data class Native(val small: Boolean, val large: Boolean, val special: Boolean)
    data class State(
        val native: Native?, val name: String?, val cellular: Boolean?, val inService: Boolean?,
        val satellite: Boolean?, val visible: Boolean?, val wifi: Boolean?, val data: Boolean?,
    )

    // null means pass through original flows; false states must never reuse an old label.
    fun resolve(mode: Int, separate: Boolean, state: State): Native? {
        val native = state.native ?: return null
        if (mode !in 0..4 || mode == 0 && !separate) return null
        if (mode == 3) return Native(false, false, false)
        if (state.cellular != true || state.satellite != false || state.inService == null || state.visible == null) return null
        if (!state.inService || !state.visible) return Native(false, false, false)
        val name = state.name ?: return null
        if (name.isBlank()) return Native(false, false, false)
        val show = when (mode) {
            0 -> native.small || native.large || native.special
            1 -> true
            2 -> state.wifi?.not() ?: return null
            4 -> when {
                state.wifi == true || state.data == false -> false
                state.wifi == false && state.data == true -> true
                else -> return null
            }
            else -> return null
        }
        return if (separate) Native(false, show, false)
        else Native(show && !native.large && !native.special, show && native.large, show && native.special)
    }

    fun <T> order(children: List<T>, text: T, signal: T, left: Boolean, rtl: Boolean): List<T> {
        if (text !in children || signal !in children || text == signal) return children
        return children.toMutableList().apply {
            remove(text)
            add(indexOf(signal) + if (left != rtl) 0 else 1, text)
        }
    }
}

/** Restore only writes still owned by us; newer host writes establish a new baseline. */
internal class MobileTypeOwnedValue<T> {
    private var active = false
    private var baseline: T? = null
    private var owned: T? = null

    fun apply(current: T, transform: (T) -> T): T {
        if (!active || current != owned) baseline = current
        @Suppress("UNCHECKED_CAST")
        val next = transform(baseline as T)
        owned = next
        active = true
        return next
    }

    fun restore(current: T): T {
        @Suppress("UNCHECKED_CAST")
        val result = if (active && current == owned) baseline as T else current
        active = false
        baseline = null
        owned = null
        return result
    }
}
