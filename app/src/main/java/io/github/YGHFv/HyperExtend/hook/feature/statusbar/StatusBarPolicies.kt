/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object ScreenshotLayerPolicy {
    fun exclusions(original: Array<String>?, rearDisplay: Boolean): Array<String>? =
        if (rearDisplay) original else (original.orEmpty().toList() + "StatusBar").distinct().toTypedArray()
}

internal data class ClockStyle(val bold: Boolean = false, val size: Int = 12,
    val left: Int = 0, val right: Int = 0, val vertical: Int = 12) {
    val changed get() = bold || size != 12 || left != 0 || right != 0 || vertical != 12
}

internal object ClockTickPolicy {
    fun needsSeconds(pattern: String): Boolean {
        var quoted = false
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\'' -> if (i + 1 < pattern.length && pattern[i + 1] == '\'') i++ else quoted = !quoted
                's', 'S' -> if (!quoted) return true
            }
            i++
        }
        return false
    }
    fun delay(nowMillis: Long) = 1000L - Math.floorMod(nowMillis, 1000L)
}

internal object BatteryOrderPolicy {
    fun shouldSwap(icon: Int, percent: Int) = icon >= 0 && percent > icon
}
