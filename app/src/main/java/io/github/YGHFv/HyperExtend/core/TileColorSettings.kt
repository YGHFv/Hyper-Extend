/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object TileColorSettings {
    const val FEATURE = "control_center_tile_color"
    const val BACKGROUND = "$FEATURE.background"
    const val ICON = "$FEATURE.icon"
    val config = listOf(
        HyperColor(BACKGROUND, "已启用小磁贴背景颜色",
            summary = "留空跟随系统；只改普通启用背景，警告/策略限制颜色优先，请自行保持对比度"),
        HyperColor(ICON, "已启用小磁贴通用图标颜色",
            summary = "留空跟随系统；仅普通矢量图标，手电筒/静音等专用颜色保留。改色或关闭等待原生重新着色"),
    )
}
