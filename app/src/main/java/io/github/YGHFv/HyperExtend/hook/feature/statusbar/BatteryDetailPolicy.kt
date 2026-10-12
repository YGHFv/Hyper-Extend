/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class BatteryDetailSample(val microamps: Int?, val millivolts: Int?,
    val temperature: Int?, val status: Int, val plugged: Int, val timestamp: Long)

internal data class BatteryDetailFormat(val content: Int = 1, val hideUnit: Int = 0,
    val decimal: Boolean = false, val positive: Boolean = false, val ratio: Boolean = false,
    val single: Boolean = false, val reverse: Boolean = false, val chargingOnly: Boolean = false)

internal object BatteryDetailPolicy {
    fun text(sample: BatteryDetailSample?, now: Long, interval: Long,
        format: BatteryDetailFormat, locale: Locale): String? {
        sample ?: return null
        if (now < sample.timestamp || now - sample.timestamp > interval.coerceIn(1_000, 10_000) + 2_000 ||
            format.chargingOnly && (sample.status != 2 || sample.plugged !in setOf(1, 2, 4, 8))) return null
        val raw = sample.microamps?.takeUnless { it == Int.MIN_VALUE }?.toLong()
        val microamps = raw?.let { if (format.ratio) it * 1_000 else it }
            ?.takeIf { abs(it) <= 30_000_000 }
        // BatteryManager's contract is positive while charging, unlike the upstream sysfs driver.
        val milliamps = microamps?.let { (it / 1_000.0).roundToLong() }
            ?.let { if (format.positive) abs(it) else it }
        val unit = format.hideUnit
        val current = milliamps?.let {
            val amperes = abs(it) > 999
            (if (amperes) String.format(locale, "%.2f", it / 1_000.0) else it.toString()) +
                (if (unit == 1 || unit == 3) "" else if (amperes) "A" else "mA")
        }
        val temperature = sample.temperature?.takeIf { it in -500..1_000 }?.let {
            (if (!format.decimal && it % 10 == 0) (it / 10).toString()
            else String.format(locale, "%.1f", it / 10.0)) + if (unit == 1 || unit == 2) "" else "\u2103"
        }
        val power = sample.millivolts?.takeIf { it in 2_000..20_000 }?.let { voltage ->
            microamps?.let { String.format(locale, "%.2f", abs(it) / 1_000_000.0 * voltage / 1_000.0) +
                if (unit == 1 || unit == 3) "" else "W" }
        }
        val parts = when (format.content) {
            2 -> listOf(power)
            3 -> listOf(current)
            4 -> listOf(temperature, power)
            5 -> listOf(current, power)
            else -> listOf(temperature, current)
        }.filterNotNull()
        return parts.takeIf { it.isNotEmpty() }?.let {
            (if (format.reverse) it.reversed() else it).joinToString(if (format.single) " " else "\n")
        }
    }

    fun width(rootWidth: Int, fixedParentWidth: Int?, siblingWidth: Int, density: Float): Int {
        if (rootWidth <= 0 || !density.isFinite() || density <= 0f || density > 10f) return 0
        val cap = minOf(rootWidth / 4, (120 * density).toInt())
        return minOf(cap, fixedParentWidth?.minus(siblingWidth)?.coerceAtLeast(0) ?: cap)
    }
}
