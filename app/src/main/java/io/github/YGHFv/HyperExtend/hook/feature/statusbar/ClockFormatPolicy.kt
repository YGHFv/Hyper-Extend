/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal data class ClockFormatPolicy(
    val style: Int = 0, val syncBig: Boolean = false, val hidePad: Boolean = false,
    val status: String = "", val big: String = "", val mini: String = "", val pad: String = "",
) {
    fun pattern(name: String, is24: Boolean): String? {
        fun single(raw: String) = raw.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }
        return when (name) {
            "clock" -> if (style in 1..2) {
                val time = single(status) ?: if (is24) "HH:mm" else "h:mm"
                val date = single(mini) ?: "M/d E"
                if (style == 1) "$time\n$date" else "$date\n$time"
            } else status.takeIf { it.isNotBlank() }
            "big_time" -> if (syncBig) single(status) else big.takeIf { it.isNotBlank() }
            "date_time" -> mini.takeIf { it.isNotBlank() }
            "pad_clock" -> if (hidePad) null else pad.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun needsSeconds(name: String): Boolean = pattern(name, true)?.let(ClockTickPolicy::needsSeconds) == true
    val changed get() = style in 1..2 || syncBig || hidePad || listOf(status, big, mini, pad).any { it.isNotBlank() }
}

/** Formatting may use the host calendar, but must not leave its shared clock advanced. */
internal object ClockCalendarScope {
    fun <T> atTime(read: () -> Long, write: (Long) -> Unit, now: Long, format: () -> T): T {
        val previous = read()
        return try { write(now); format() } finally { write(previous) }
    }
}

/** TextView may wrap a String in SpannedString; compare content, retain native spans. */
internal class ClockTextOverride {
    private var native: CharSequence? = null
    private var owned: String? = null

    fun apply(current: CharSequence, next: String): CharSequence {
        if (owned == null || current.toString() != owned) native = current
        owned = next
        return next
    }

    fun restore(current: CharSequence): CharSequence {
        val result = if (owned != null && current.toString() == owned) native ?: current else current
        native = null
        owned = null
        return result
    }
}
