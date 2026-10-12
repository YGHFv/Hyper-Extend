/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object BatteryDetailSettings {
    const val FEATURE = "status_bar_battery_detail"
    val options = listOf(
        HyperOption("$FEATURE.temp_decimal", "温度总显示一位小数", defaultEnabled = false),
        HyperOption("$FEATURE.positive", "电流总显示正值", defaultEnabled = false),
        HyperOption("$FEATURE.fix_current_ratio", "修复电流值比例", defaultEnabled = false,
            summary = "仅供错误地返回毫安值的设备使用；正常 BatteryManager 返回微安，不应开启"),
        HyperOption("$FEATURE.single_row", "单排显示", defaultEnabled = false),
        HyperOption("$FEATURE.reverse_order", "反序", defaultEnabled = false),
        HyperOption("$FEATURE.in_charge", "仅在充电时显示", defaultEnabled = false),
        HyperOption("$FEATURE.bold", "字体加粗", defaultEnabled = false),
    )
    val config = listOf(
        HyperChoice("$FEATURE.content", "显示内容", listOf(
            ChoiceEntry("1", "温度与电流"), ChoiceEntry("2", "功率"), ChoiceEntry("3", "电流"),
            ChoiceEntry("4", "温度与功率"), ChoiceEntry("5", "电流与功率"))),
        HyperChoice("$FEATURE.hide_unit", "隐藏单位", listOf(
            ChoiceEntry("0", "不隐藏"), ChoiceEntry("1", "全部"), ChoiceEntry("2", "温度"),
            ChoiceEntry("3", "电流与功率"))),
        HyperChoice("$FEATURE.align", "水平对齐", listOf(
            ChoiceEntry("1", "默认"), ChoiceEntry("2", "起始侧"),
            ChoiceEntry("3", "居中"), ChoiceEntry("4", "末尾侧"))),
        HyperSlider("$FEATURE.update_spacing", "刷新间隔", 1, 10, 2, unit = " s"),
        HyperSlider("$FEATURE.font_size", "字体大小", 14, 40, 16, divisor = 2, unit = " dp"),
        HyperSlider("$FEATURE.fixed_width", "固定宽度", 10, 120, 10, unit = " dp",
            summary = "10 为自适应；始终受当前状态栏可用宽度限制"),
        HyperSlider("$FEATURE.left_margin", "左边距", 0, 10, 8, divisor = 2, unit = " dp"),
        HyperSlider("$FEATURE.right_margin", "右边距", 0, 10, 8, divisor = 2, unit = " dp"),
        HyperSlider("$FEATURE.vertical_offset", "上下偏移量", 0, 20, 8, divisor = 2,
            unit = " dp", summary = "8 为原位"),
    )
}
