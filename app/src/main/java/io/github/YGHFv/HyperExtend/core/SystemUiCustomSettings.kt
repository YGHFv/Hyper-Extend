/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

const val SYSTEM_UI_MONET_COLOR = "systemui_monet_custom.color"
const val SYSTEM_UI_FOCUS_PACKAGES = "notification_unlock_focus.packages"

internal object SystemUiCustomSettings {
    fun serializePackages(packages: Set<String>): String = packages.sorted().joinToString("\n")

    fun colorText(color: Int): String = "#%06X".format(java.util.Locale.ROOT, color and 0xffffff)

    fun color(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if (!hex.matches(Regex("[a-fA-F0-9]{6}"))) return null
        return hex.toInt(16) or 0xff000000.toInt()
    }

    fun packages(value: String): Set<String> = value.split(Regex("[\\s,;]+"))
        .filter { it == "android" || it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) }
        .toSet()
}
