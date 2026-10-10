/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object DualRowSignalSettings {
    const val FEATURE = "status_bar_dual_row_signal"
    const val STYLE = "$FEATURE.style"
    const val SCALE = "$FEATURE.scale"
    const val LEFT = "$FEATURE.left"
    const val RIGHT = "$FEATURE.right"
    const val VERTICAL = "$FEATURE.vertical"

    fun compatible(mobileEnabled: Boolean, mode: String?, hideFirst: Boolean, hideSecond: Boolean): Boolean =
        !mobileEnabled || (MobileSignalSettings.mode(mode) == 0 && !hideFirst && !hideSecond)

    fun style(value: String): String = value.takeIf { it in setOf("classic", "thick", "theme") }.orEmpty()
}
