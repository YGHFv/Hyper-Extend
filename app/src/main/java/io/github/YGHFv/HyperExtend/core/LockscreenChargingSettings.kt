/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object LockscreenChargingSettings {
    const val FEATURE = "lockscreen_charging_info"
    const val MILLIAMPS = "$FEATURE.milliamps"
    const val TEMPERATURE = "$FEATURE.temperature"
    const val CUSTOM_INTERVAL = "$FEATURE.custom_interval"
    const val INTERVAL = "$FEATURE.interval"
    fun interval(raw: String?, custom: Boolean): Long =
        (if (custom) raw?.toIntOrNull()?.coerceIn(2, 10) ?: 6 else 6) * 500L

    val options = listOf(
        HyperOption(MILLIAMPS, "以 mA 显示详细电流", "显示电流绝对值，不推断厂商电流正负方向", defaultEnabled = false),
        HyperOption(TEMPERATURE, "显示电池温度", "无有效温度数据时不显示，不用 0 度占位", defaultEnabled = false),
        HyperOption(CUSTOM_INTERVAL, "自定义刷新间隔", "关闭时每 3 秒采样；仅亮屏且原生充电提示实际显示时运行", defaultEnabled = false),
    )
    val config = listOf(HyperSlider(INTERVAL, "刷新间隔", 2, 10, 6, divisor = 2, unit = " s",
        summary = "启用自定义刷新间隔后生效，支持 0.5 秒步进"))
}
