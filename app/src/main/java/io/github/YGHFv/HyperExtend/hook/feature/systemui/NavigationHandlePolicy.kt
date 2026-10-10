/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NavigationHandlePolicy {
    fun supported(version: Long?, mainThread: Boolean, hidden: Boolean): Boolean =
        version == 202602260L && mainThread && !hidden

    fun radiusPixels(dp: Float, density: Float): Float? =
        if (dp.isFinite() && dp in 0f..5f && density.isFinite() && density > 0f)
            (dp * density).takeIf { it.isFinite() } else null
}

/** Restore even when preparation or the original method fails; never replay the host call. */
internal inline fun <T> withNavigationHandleOverride(prepare: () -> Unit, restore: () -> Unit, block: () -> T): T =
    try { prepare(); block() } finally { restore() }
