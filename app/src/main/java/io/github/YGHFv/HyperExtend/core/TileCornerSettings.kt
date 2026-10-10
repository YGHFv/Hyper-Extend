/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object TileCornerSettings {
    const val FEATURE = "control_center_tile_corners"
    val radius = HyperSlider("$FEATURE.radius", "圆角半径（像素）", 1, 99, 72,
        summary = "沿用上游像素单位；最大不超过磁贴边长的一半，仅普通背景生效")
    val config = listOf(radius)
    fun value(raw: String): Int = raw.toIntOrNull()?.coerceIn(radius.min, radius.max) ?: radius.default
}
