/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object MobileTypeDisplaySettings {
    const val FEATURE = "status_bar_mobile_type_display"
    const val MODE = "$FEATURE.mode"
    const val SEPARATE = "$FEATURE.separate"
    const val LEFT = "$FEATURE.left"
    const val BOLD = "$FEATURE.bold"
    const val SIZE = "$FEATURE.size"
    const val LEFT_MARGIN = "$FEATURE.left_margin"
    const val RIGHT_MARGIN = "$FEATURE.right_margin"
    const val VERTICAL = "$FEATURE.vertical"

    fun mode(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in 0..4 } ?: 0
}
