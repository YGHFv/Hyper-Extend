/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.LockscreenShortcutSettings

internal object LockscreenShortcutPolicy {
    /**
     * 左侧按钮合并后只剩一张卡片（见 [LockscreenShortcutSettings]）：主键保留老「隐藏」卡片的键，
     * 「隐藏 / 手电筒」改由卡片的 `mode` 选择项区分。右侧按钮作用在另一个位置，仍是独立卡片。
     */
    const val LEFT = LockscreenShortcutSettings.FEATURE
    const val RIGHT = "lockscreen_hide_right_shortcut"
    const val PACKAGE = "com.miui.aod"
    val features = listOf(LEFT, RIGHT)

    /** 安装器已完成宿主与插件目标的结构解析；这里不再重复读取包版本。 */
    fun supported(): Boolean = true
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
