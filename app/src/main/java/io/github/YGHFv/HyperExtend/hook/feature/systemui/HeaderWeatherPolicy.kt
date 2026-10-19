/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object HeaderWeatherPolicy {
    const val REFRESH_MS = 15 * 60 * 1000L
    const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    fun eligible(enabled: Boolean, display: Int?, currentUser: Int?, trackerReady: Boolean) =
        enabled && display == 0 && currentUser != null && currentUser >= 0 && trackerReady

    fun text(valid: Boolean, temperature: Int, unit: Int, description: String?, city: String?,
             showCity: Boolean, updated: Long, now: Long): String? {
        if (!valid || unit !in 0..1 || temperature !in -150..150 || updated <= 0 ||
            now < updated || now - updated > MAX_AGE_MS) return null
        fun clean(value: String?, limit: Int) = value.orEmpty()
            .filterNot { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
            .trim().take(limit)
        return listOf(if (showCity) clean(city, 24) else "", clean(description, 24),
            "$temperature\u00b0${if (unit == 1) "C" else "F"}").filter { it.isNotBlank() }.joinToString(" ")
    }

    data class Space(val left: Int, val right: Int)
    fun space(width: Int, left: Int, right: Int, rtl: Boolean, gap: Int, obstacles: List<Space>): Space? {
        if (width <= 0 || left < 0 || right < left || right > width || gap < 0) return null
        val start = if (rtl) 0 else right + gap
        val end = if (rtl) left - gap else width
        var a = start
        var b = end
        for (obstacle in obstacles) {
            if (obstacle.right <= start || obstacle.left >= end) continue
            if (rtl) a = maxOf(a, obstacle.right + gap) else b = minOf(b, obstacle.left - gap)
        }
        return if (b > a) Space(a, b) else null
    }
}
