/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object AlarmWindowPolicy {
    fun hide(trigger: Long?, now: Long, hours: Int, sampledAt: Long, elapsed: Long): Boolean {
        if (hours !in 1..48 || trigger == null || trigger <= now || now < 0 ||
            elapsed < sampledAt || elapsed - sampledAt > 35_000) return false
        return trigger - now > hours * 3_600_000L + 59_000L
    }
}
