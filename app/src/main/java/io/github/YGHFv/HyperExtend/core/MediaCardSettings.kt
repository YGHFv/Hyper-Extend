/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

object MediaCardSettings {
    const val LAYOUT = "media_card_layout"
    const val ALBUM = "$LAYOUT.album"
    const val ORDER = "$LAYOUT.order"
    const val LEFT_ALIGNED = "$LAYOUT.left_aligned"
    const val HIDE_SEAMLESS = "$LAYOUT.hide_seamless"
    const val TITLE_MARGIN = "$LAYOUT.title_margin"
    const val ARTIST_MARGIN = "$LAYOUT.artist_margin"
    const val TEXT_SIZE = "media_card_text_size"
    const val TITLE_SIZE = "$TEXT_SIZE.title"
    const val ARTIST_SIZE = "$TEXT_SIZE.artist"
    const val TIME_SIZE = "$TEXT_SIZE.time"
    const val BUTTON_SIZE = "$LAYOUT.button_size"
    const val CUSTOM_BUTTON_SIZE = "$LAYOUT.custom_button_size"

    val buttonSize = HyperSlider(BUTTON_SIZE, "主要操作图标大小", 50, 200, 140, unit = " px",
        summary = "140 为系统默认；只缩放图标，不缩小触摸区域")
    val customButtonSize = HyperSlider(CUSTOM_BUTTON_SIZE, "自定义操作图标大小", 50, 200, 140, unit = " px",
        summary = "140 为跟随主要操作；保留原生动画与点击行为")

    val titleMargin = HyperSlider(TITLE_MARGIN, "标题顶部间距", 0, 480, 210, divisor = 10, unit = " dp",
        summary = "21.0 dp 为不覆盖系统值")
    val artistMargin = HyperSlider(ARTIST_MARGIN, "标题与艺术家间距", 0, 360, 40, divisor = 10, unit = " dp",
        summary = "4.0 dp 为不覆盖系统值")
    val titleSize = HyperSlider(TITLE_SIZE, "标题字号", 120, 360, 180, divisor = 10, unit = " sp")
    val artistSize = HyperSlider(ARTIST_SIZE, "艺术家字号", 80, 240, 120, divisor = 10, unit = " sp")
    val timeSize = HyperSlider(TIME_SIZE, "播放时间字号", 50, 200, 130, divisor = 10, unit = " sp")

    fun value(raw: String, slider: HyperSlider): Int = raw.toIntOrNull()?.coerceIn(slider.min, slider.max) ?: slider.default
    fun mode(raw: String): Int = raw.toIntOrNull()?.takeIf { it in 0..2 } ?: 0
}
