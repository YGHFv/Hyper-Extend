/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object MobileTypeTextSettings {
    const val FEATURE = "status_bar_mobile_type_text"
    const val TEXT = "$FEATURE.text"

    // The native status-bar drawable is single-line and shares limited width with privacy icons.
    fun text(raw: String): String? {
        // Validate before trimming so pasted boundary newlines cannot bypass the single-line rule.
        if (raw.codePoints().anyMatch { point ->
                Character.isISOControl(point) || when (Character.getType(point)) {
                    Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(),
                    Character.PARAGRAPH_SEPARATOR.toInt(), Character.SURROGATE.toInt() -> true
                    else -> false
                }
            }) return null
        val value = raw.trim()
        if (value.isEmpty() || value.codePointCount(0, value.length) > 8) return null
        return value
    }

    fun replacement(original: String?, networkType: Int, custom: String): String? =
        if (networkType < 0 || original.isNullOrEmpty()) original else custom
}
