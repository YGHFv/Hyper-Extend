/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TileCornerPolicy {
    fun ownsRadius(current: Float, perCorner: Boolean, applied: Float): Boolean =
        !perCorner && current == applied

    fun radius(requested: Int, size: Float): Float? =
        if (requested !in 1..99 || !size.isFinite() || size <= 0f) null else minOf(requested.toFloat(), size / 2f)

    fun eligible(enabled: Boolean, systemUi: Long?, plugin: Long?, display: Int?, card: Boolean,
        detail: Boolean, defaultTheme: Boolean, material: Boolean): Boolean =
        enabled && systemUi == 202602260L && plugin == 183022200L && display == 0 &&
            !card && !detail && defaultTheme && !material
}
