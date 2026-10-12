/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object MediaProgressSettings {
    const val FEATURE = "media_card_progress"
    val config = listOf(
        HyperChoice("$FEATURE.mode", "进度条样式", listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "波浪线"), ChoiceEntry("2", "自定义直线"))),
        HyperChoice("$FEATURE.thumb", "进度按钮", listOf(
            ChoiceEntry("0", "圆形"), ChoiceEntry("1", "竖条"), ChoiceEntry("2", "隐藏"))),
        HyperSlider("$FEATURE.height", "进度条高度", 10, 160, 80, divisor = 10, unit = " dp"),
        HyperSlider("$FEATURE.corner", "自定义直线圆角", 12, 90, 36, divisor = 10, unit = " dp"),
    )
    val options = listOf(
        HyperOption("$FEATURE.comet", "彗星进度条", defaultEnabled = false,
            summary = "仅自定义直线样式，已播放部分从细尾逐渐过渡到进度头"),
        HyperOption("$FEATURE.round", "圆角进度按钮", defaultEnabled = false,
            summary = "自定义样式中选择隐藏按钮时，以圆角矩形代替完全隐藏"),
    )
    fun mode(raw: String): Int = raw.toIntOrNull()?.takeIf { it in 0..2 } ?: 0
}
