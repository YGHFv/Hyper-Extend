/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import java.util.Locale

internal object NetworkSpeedText {
    fun hideUnit(style: Int, unit: String?): Boolean = style in 1..4 && unit?.isEmpty() == true
    fun lineSpacing(stored: Int): Float = stored.coerceIn(14, 22) * 0.05f
    data class Options(val style: Int, val icon: Int = 2, val hide: Boolean = false,
        val hideAll: Boolean = false, val swap: Boolean = false, val lowLevel: Long = 65536,
        val suffix: String = "B/s")

    fun render(tx: Long, rx: Long, options: Options): Array<String>? = with(options) {
        val sent = tx.coerceAtLeast(0)
        val received = rx.coerceAtLeast(0)
        val total = if (Long.MAX_VALUE - sent < received) Long.MAX_VALUE else sent + received
        if (style == 0) return if (hide && total < lowLevel) arrayOf("", "") else null
        fun format(bytes: Long): String {
            val (divisor, unit) = when {
                bytes >= 1073741824L -> 1073741824.0 to "G"
                bytes >= 1048576L -> 1048576.0 to "M"
                else -> 1024.0 to "K"
            }
            val value = bytes.coerceAtLeast(0) / divisor
            val number = String.format(Locale.US, if (value < 100) "%.1f" else "%.0f", value)
            return number + (if (style == 2) "\n" else "") + unit + suffix
        }
        fun direction(bytes: Long, up: Boolean): String {
            val low = bytes < lowLevel
            if (hide && !hideAll && low) return ""
            val arrow = when (icon) {
                2 -> if (up) (if (low) "\u25b3" else "\u25b2") else (if (low) "\u25bd" else "\u25bc")
                3 -> if (up) (if (low) " \u25b5" else " \u25b4") else (if (low) " \u25bf" else " \u25be")
                4 -> if (up) (if (low) " \u2616" else " \u2617") else (if (low) " \u26c9" else " \u26ca")
                5 -> if (up) "\u2191" else "\u2193"
                6 -> if (up) "\u21e7" else "\u21e9"
                else -> ""
            }
            return if (swap) arrow + format(bytes) else format(bytes) + arrow
        }
        val text = when {
            style in 1..2 -> if (hide && total < lowLevel) "" else format(total)
            hide && hideAll && tx < lowLevel && rx < lowLevel -> ""
            else -> listOf(direction(tx, true), direction(rx, false)).let {
                if (style == 3) it.filter(String::isNotEmpty).joinToString(" ")
                else if (it.all(String::isEmpty)) "" else it.joinToString("\n")
            }
        }
        // NetworkSpeedController always reads both indices, even for empty text.
        arrayOf(text, "")
    }
}

internal class NetworkSpeedSampler {
    var valid: Boolean = false
        private set
    private var last: Long? = null
    private var tx = 0L
    private var rx = 0L
    private var loopbackTx = 0L
    private var loopbackRx = 0L
    private var speed = 0L to 0L

    fun sample(now: Long, sent: Long, received: Long, loopbackSent: Long = 0,
               loopbackReceived: Long = 0): Pair<Long, Long> {
        val elapsed = last?.let { now - it }
        if (sent < 0 || received < 0 || loopbackSent !in 0..sent || loopbackReceived !in 0..received) {
            last = null; valid = false; speed = 0L to 0L; return speed
        }
        val countersAdvanced = sent >= tx && received >= rx && loopbackSent >= loopbackTx &&
            loopbackReceived >= loopbackRx
        val sentDelta = sent - tx
        val receivedDelta = received - rx
        val loopbackSentDelta = loopbackSent - loopbackTx
        val loopbackReceivedDelta = loopbackReceived - loopbackRx
        val coherent = countersAdvanced && sentDelta >= loopbackSentDelta && receivedDelta >= loopbackReceivedDelta
        if (elapsed != null && elapsed in 0 until 150_000_000 && coherent) return speed
        // The exposed maximum cadence is 10s; allow dispatch jitter before treating it as sleep.
        valid = elapsed != null && elapsed in 1..30_000_000_000 && coherent
        speed = if (!valid) {
            0L to 0L
        } else {
            val seconds = requireNotNull(elapsed) / 1e9
            ((sentDelta - loopbackSentDelta) / seconds).toLong() to
                ((receivedDelta - loopbackReceivedDelta) / seconds).toLong()
        }
        last = now; tx = sent; rx = received
        loopbackTx = loopbackSent; loopbackRx = loopbackReceived
        return speed
    }
}

internal object NetworkSpeedSchedule {
    const val SAMPLE = 200001
    fun shouldReschedule(what: Int, background: Boolean, hidden: Boolean, pending: Boolean) =
        what == SAMPLE && background && !hidden && pending
}
