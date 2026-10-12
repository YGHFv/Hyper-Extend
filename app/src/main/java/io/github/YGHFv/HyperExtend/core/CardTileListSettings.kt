/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object CardTileListSettings {
    const val FEATURE = "control_center_card_tiles"
    const val ORDER = "$FEATURE.order"
    val specs = setOf("wifi", "cell", "bt", "gps", "nfc", "hotspot", "flashlight",
        "settings", "airplane", "autobrightness")
    val config = listOf(HyperText(ORDER, "卡片顺序",
        summary = "英文逗号分隔：wifi、cell、bt、gps、nfc、hotspot、flashlight、settings、airplane、autobrightness。留空跟随系统；none 隐藏普通卡片。VoWiFi 保留原生自动管理，不写入这里。",
        placeholder = "wifi,cell,bt,flashlight"))

    fun parse(raw: String): List<String>? {
        if (raw.isBlank() || raw.length > 256) return null
        if (raw.trim() == "none") return emptyList()
        val values = raw.split(',').map { it.trim() }
        if (values.size > specs.size || values.any { it !in specs }) return null
        return values.distinct()
    }
}
