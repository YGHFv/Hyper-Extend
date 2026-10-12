/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object IslandMediaSettings {
    const val LAYOUT = "island_media_layout"
    const val TEXT = "island_media_text"
    const val PROGRESS = "island_media_progress"
    const val BACKGROUND = "island_media_background"
    val background = listOf(
        HyperChoice("$BACKGROUND.mode", "背景样式", listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "封面拼色"), ChoiceEntry("2", "模糊封面"),
            ChoiceEntry("3", "径向渐变"), ChoiceEntry("4", "线性渐变"))),
        HyperChoice("$BACKGROUND.ambient", "原生背景氛围光", listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "隐藏"), ChoiceEntry("2", "自定义"))),
        HyperSlider("$BACKGROUND.blur", "封面模糊强度", 0, 20, 10, unit = " %"),
    )
    val backgroundOptions = listOf(
        HyperOption("$BACKGROUND.transition", "封面与前景配色过渡", defaultEnabled = false),
        HyperOption("$BACKGROUND.inverse", "线性渐变自动反色", defaultEnabled = false),
        HyperOption("$BACKGROUND.optimized", "氛围光莫奈取色", defaultEnabled = false),
    )
    val options = listOf(
        HyperOption("$LAYOUT.left_aligned", "操作图标左对齐", defaultEnabled = false),
        HyperOption("$LAYOUT.hide_seamless", "隐藏媒体输出入口", defaultEnabled = false),
    )
    val layout = listOf(
        HyperChoice("$LAYOUT.album", "专辑显示", entries = listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "隐藏应用标记"), ChoiceEntry("2", "隐藏专辑"))),
        HyperChoice("$LAYOUT.order", "操作顺序", entries = listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "上一首在前"), ChoiceEntry("2", "播放在前"))),
        HyperSlider("$LAYOUT.title_margin", "标题顶部间距", 0, 480, 210, divisor = 10, unit = " dp",
            summary = "21.0 保留当前宿主原生值"),
        HyperSlider("$LAYOUT.artist_margin", "标题与艺术家间距", 0, 360, 20, divisor = 10, unit = " dp",
            summary = "2.0 保留当前宿主原生值；不导入上游 XML 与 hook 不一致的默认数值"),
        HyperSlider("$LAYOUT.button_size", "主要操作图标大小", 50, 200, 140, unit = " px",
            summary = "140 保留原生，仅缩放图标，尺寸限制在原生内容区域内"),
        HyperSlider("$LAYOUT.custom_button_size", "自定义操作图标大小", 50, 200, 140, unit = " px",
            summary = "140 跟随主要操作，保留 Drawable 动画与点击"),
    )
    val text = listOf(
        HyperSlider("$TEXT.title", "标题字号", 120, 360, 180, divisor = 10, unit = " sp"),
        HyperSlider("$TEXT.artist", "艺术家字号", 80, 240, 120, divisor = 10, unit = " sp"),
        HyperSlider("$TEXT.time", "播放时间字号", 50, 200, 130, divisor = 10, unit = " sp"),
    )
    val progress = listOf(
        HyperChoice("$PROGRESS.mode", "进度条样式", listOf(
            ChoiceEntry("0", "原生"), ChoiceEntry("1", "波浪线"), ChoiceEntry("2", "自定义直线"))),
        HyperChoice("$PROGRESS.thumb", "进度按钮", listOf(
            ChoiceEntry("0", "圆形"), ChoiceEntry("1", "竖条"), ChoiceEntry("2", "隐藏"))),
        HyperSlider("$PROGRESS.height", "进度条高度", 10, 160, 60, divisor = 10, unit = " dp"),
        HyperSlider("$PROGRESS.corner", "直线圆角", 12, 90, 36, divisor = 10, unit = " dp"),
    )
    val progressOptions = listOf(
        HyperOption("$PROGRESS.comet", "彗星进度条", defaultEnabled = false),
        HyperOption("$PROGRESS.round", "圆角进度按钮", defaultEnabled = false),
    )
}
