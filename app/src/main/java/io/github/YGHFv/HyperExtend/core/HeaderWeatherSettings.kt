/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object HeaderWeatherSettings {
    const val FEATURE = "shade_header_weather"
    const val CITY = "$FEATURE.city"
    val options = listOf(HyperOption(CITY, "显示地区", "使用天气应用已有地区，不主动定位", defaultEnabled = false))
}
