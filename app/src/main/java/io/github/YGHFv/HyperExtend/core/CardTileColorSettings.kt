/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object CardTileColorSettings {
    const val FEATURE = "control_center_card_tile_color"
    const val BACKGROUND = "$FEATURE.background"
    const val FOREGROUND = "$FEATURE.foreground"
    val config = listOf(
        HyperColor(BACKGROUND, "已启用卡片背景颜色", summary = "留空跟随系统；仅普通背景，不覆盖材质、警告或策略限制"),
        HyperColor(FOREGROUND, "已启用卡片图标与文字颜色", summary = "留空跟随系统；请自行保持对比度，流量警告优先"),
    )
}
