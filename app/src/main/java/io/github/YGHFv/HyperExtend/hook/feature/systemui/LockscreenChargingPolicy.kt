/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.util.Locale
import kotlin.math.abs

internal data class ChargingSample(val currentMicroamps: Int?, val voltageMillivolts: Int?,
    val temperatureTenths: Int?, val plugged: Int, val status: Int, val health: Int, val sampledAt: Long)

internal object LockscreenChargingPolicy {
    const val BATTERY_TYPE = 3
    fun eligible(version: Long, attached: Boolean, shown: Boolean, visible: Boolean, dozing: Boolean,
        blocked: Boolean, tiny: Boolean, plugged: Boolean, defender: Boolean, reverse: Int,
        reposition: Boolean, indicationType: Int, messageMatches: Boolean): Boolean =
        version == 202602260L && attached && shown && visible && !dozing && !blocked && !tiny &&
            plugged && !defender && reverse == 0 && !reposition && indicationType == BATTERY_TYPE && messageMatches

    fun details(sample: ChargingSample?, now: Long, interval: Long, milliamps: Boolean,
        temperature: Boolean, locale: Locale): String? {
        sample ?: return null
        if (now < sample.sampledAt || now - sample.sampledAt > interval + 2_000L ||
            sample.plugged !in setOf(1, 2, 4, 8) || sample.status !in setOf(2, 5) || sample.health != 2) return null
        val parts = mutableListOf<String>()
        val current = sample.currentMicroamps?.takeIf { it != Int.MIN_VALUE }?.toLong()?.let(::abs)
            ?.takeIf { it in 1..30_000_000L }
        val voltage = sample.voltageMillivolts?.takeIf { it in 2_000..20_000 }
        if (current != null && voltage != null) {
            parts += if (milliamps) String.format(locale, "%.0f mA", current / 1_000.0)
                else String.format(locale, "%.1f A", current / 1_000_000.0)
            parts += String.format(locale, "%.2f V", voltage / 1_000.0)
            parts += String.format(locale, "~%.2f W", current / 1_000_000.0 * voltage / 1_000.0)
        }
        if (temperature) sample.temperatureTenths?.takeIf { it in -500..1_000 }?.let {
            parts += String.format(locale, "%.1f \u00b0C", it / 10.0)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
    }
}

/** Main-thread lifecycle tokens reject completion from a previous visibility/holder session. */
internal class ChargingRequestState {
    private var generation = 0L
    private var pending = false
    private var due = 0L
    fun begin(now: Long): Long? {
        if (pending || now < due) return null
        pending = true
        return ++generation
    }
    fun complete(token: Long, now: Long, interval: Long): Boolean {
        if (!pending || token != generation) return false
        pending = false; due = now + interval
        return true
    }
    fun cancel() { generation++; pending = false; due = 0 }
}
