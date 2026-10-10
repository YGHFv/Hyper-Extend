/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ClassicQsPolicy {
    fun rowLimit(requested: Int?, nativeMax: Int, nativeMin: Int): Int? {
        if (nativeMax <= 0 || nativeMin < 0 || nativeMin > nativeMax) return null
        return requested?.takeIf { it in 1..5 }?.coerceAtLeast(nativeMin) ?: nativeMax
    }

    fun redistribute(previous: Int?, effective: Int, nativeMax: Int): Boolean =
        if (previous == null) effective != nativeMax else previous != effective
}
