/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import kotlin.math.roundToInt

internal object IslandMediaPolicy {
    fun textSize(raw: Int, density: Float): Float? =
        (raw / 10f * density).takeIf { raw in 50..360 && density.isFinite() && density > 0f && it.isFinite() }

    fun mainAction(semantic: Boolean, index: Int) = semantic && index in 1..3

    fun edits(options: MediaLayoutOptions, artistMargin: Int, density: Float): List<MediaConstraintEdit> {
        // Current XML uses 2dp; upstream XML says 2dp but its hook treats 4dp as
        // default. Preserve native at our explicit 2dp default, not the mismatch.
        val result = MediaLayoutPolicy.edits(options.copy(artistMargin = 40), IslandMediaTargets.ids, density)
        return if (artistMargin == 20) result else result + MediaConstraintEdit("setMargin",
            listOf(IslandMediaTargets.ids.getValue("header_artist"), MediaLayoutPolicy.TOP,
                (artistMargin.coerceIn(0, 360) / 10f * density).roundToInt()))
    }
}

/** Tracks ownership without keeping a View, drawable or callback alive. */
internal class IslandOwnedValue<T> {
    private var native: T? = null
    private var applied: T? = null
    fun apply(current: T, desired: T): T {
        if (applied == null || current != applied) native = current
        applied = desired
        return desired
    }
    fun restore(current: T): T {
        val value = if (current == applied) native ?: current else current
        native = null; applied = null
        return value
    }
}
