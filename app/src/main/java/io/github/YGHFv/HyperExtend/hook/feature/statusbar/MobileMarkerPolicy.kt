/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object MobileMarkerPolicy {
    enum class Marker { VOWIFI, ROAMING, SMALL_ROAMING, ACTIVITY }

    fun marker(group: Boolean, id: Int): Marker? = if (group) when (id) {
        0 -> Marker.VOWIFI
        2 -> Marker.ROAMING
        else -> null
    } else when (id) {
        3 -> Marker.SMALL_ROAMING
        4 -> Marker.ACTIVITY
        else -> null
    }

    // Roaming's native writer runs for either an allowed update OR a visible result.
    // Preserve that writer gate when replacing the display result, not the modem state.
    fun hiddenPair(marker: Marker, update: Boolean, visible: Boolean): Pair<Boolean, Boolean>? = when (marker) {
        Marker.ROAMING -> Pair(update || visible, false)
        Marker.ACTIVITY -> Pair(update, false)
        else -> null
    }
}
