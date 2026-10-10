/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenShortcutPolicy {
    const val LEFT = "lockscreen_hide_left_shortcut"
    const val RIGHT = "lockscreen_hide_right_shortcut"
    const val FLASHLIGHT = "lockscreen_left_flashlight"
    const val PACKAGE = "com.miui.aod"
    val features = listOf(LEFT, RIGHT, FLASHLIGHT)

    fun replaceLeft(enabled: Boolean, hideLeft: Boolean): Boolean = enabled && !hideLeft

    fun supported(systemUi: Long?, plugin: Long?): Boolean = systemUi == 202602260L && plugin == 22446301L
    fun hide(left: Boolean?, hideLeft: Boolean, hideRight: Boolean): Boolean = when (left) {
        true -> hideLeft
        false -> hideRight
        null -> false
    }
    fun blockLaunch(leftSelected: Boolean, rightSelected: Boolean, hideLeft: Boolean, hideRight: Boolean): Boolean =
        leftSelected != rightSelected && hide(leftSelected, hideLeft, hideRight)
}

/** Restore only our last write; host changes observed between frames become the new baseline. */
internal class ShortcutVisibilityState {
    private var native: Int? = null
    fun hide(current: Int): Int {
        if (native == null || current != GONE) native = current
        return GONE
    }
    fun restore(current: Int): Int = (if (current == GONE) native ?: current else current).also { native = null }
    private companion object { const val GONE = 8 }
}
