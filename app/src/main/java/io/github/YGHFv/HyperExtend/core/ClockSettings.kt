/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object ClockSettings {
    const val FEATURE = "status_bar_clock"
    const val STYLE = "$FEATURE.style"
    const val ALIGN = "$FEATURE.alignment"
    const val SPACING = "$FEATURE.line_spacing"
    const val WIDTH = "$FEATURE.fixed_width"
    const val SYNC = "$FEATURE.big_format_mode"
    const val HIDE_PAD = "$FEATURE.pad_hide"

    data class Role(val suffix: String, val title: String, val defaultSize: Int, val minSize: Int, val maxSize: Int, val maxMargin: Int) {
        fun key(option: String) = "$FEATURE.${if (suffix.isEmpty()) "" else "$suffix."}$option"
    }
    val status = Role("", "状态栏时钟", 12, 7, 20, 40)
    val big = Role("big", "通知中心大时钟", 50, 20, 66, 60)
    val mini = Role("mini", "通知中心迷你/横屏时钟", 12, 7, 20, 40)
    val pad = Role("pad", "平板日期时钟", 12, 7, 20, 40)
    val roles = listOf(status, big, mini, pad)

    fun role(name: String): Role? = when (name) {
        "clock" -> status
        "big_time" -> big
        "date_time", "horizontal_time" -> mini
        "pad_clock" -> pad
        else -> null
    }

    fun choice(raw: String?, max: Int) = raw?.toIntOrNull()?.takeIf { it in 0..max } ?: 0

    val options: List<HyperOption> = roles.map {
        HyperOption(it.key("bold"), "加粗", group = it.title, defaultEnabled = false)
    } + HyperOption(HIDE_PAD, "隐藏平板日期时钟", "只隐藏 pad_clock，不影响主时钟", group = pad.title, defaultEnabled = false)

    val config: List<HyperConfigRow> = buildList {
        add(HyperChoice(STYLE, "状态栏时钟排列", group = "排列与格式", entries = listOf(
            ChoiceEntry("0", "单行/原有格式"), ChoiceEntry("1", "时间在上，日期在下"), ChoiceEntry("2", "日期在上，时间在下"))))
        add(HyperChoice(SYNC, "通知中心大时钟格式", group = "排列与格式", summary = "默认保留本模块已有独立格式；同步只取状态栏时间行", entries = listOf(
            ChoiceEntry("0", "独立设置（保持原配置）"), ChoiceEntry("1", "同步状态栏时间格式"))))
        add(HyperChoice(ALIGN, "双行对齐", group = status.title, entries = listOf(
            ChoiceEntry("0", "左对齐"), ChoiceEntry("1", "居中"), ChoiceEntry("2", "右对齐"))))
        add(HyperSlider(SPACING, "双行行距倍率", 14, 32, 16, divisor = 20, unit = " 倍", group = status.title,
            summary = "仅双行生效；这是行距倍率，不是 dp"))
        add(HyperSlider(WIDTH, "固定宽度", 30, 120, 30, unit = " dp", group = status.title,
            summary = "30 表示保持原生宽度；更大的值固定占用宽度"))
        roles.forEach { role ->
            add(HyperSlider(role.key("size"), "时钟大小", role.minSize, role.maxSize, role.defaultSize, unit = " dp", group = role.title))
            for ((key, title) in listOf("left_margin" to "左边距", "right_margin" to "右边距")) {
                add(HyperSlider(role.key(key), title, 0, role.maxMargin, 0, unit = " dp", group = role.title))
            }
            // The existing status-bar key stores 12 as zero. Keep that encoding for backups.
            if (role == status) add(HyperSlider(role.key("vertical_offset"), "上下偏移量", 0, 24, 12,
                divisor = 2, unit = " dp", group = role.title, summary = "历史编码：6.0 为居中，向两侧调整各 6 dp"))
            else add(HyperSlider(role.key("vertical_offset"), "上下偏移量", -12, 12, 0, divisor = 2, unit = " dp", group = role.title))
        }
        for ((suffix, title) in listOf("s" to status.title, "b" to big.title, "n" to "通知中心迷你时钟", "p" to pad.title)) {
            add(HyperText("$FEATURE.editor_$suffix", "${title}格式", group = "时钟格式",
                summary = if (suffix == "n") "留空使用原生格式；双行日期默认 M/d E" else "留空使用原生格式",
                placeholder = if (suffix in listOf("s", "b")) "HH:mm" else "M/d E"))
        }
    }
}
