/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class TileCornerPolicyTest {
    @Test fun radiusIsBoundedByGeometry() {
        assertEquals(72f, TileCornerPolicy.radius(72, 200f)!!, 0f)
        assertEquals(40f, TileCornerPolicy.radius(72, 80f)!!, 0f)
        assertEquals(1f, TileCornerPolicy.radius(1, 80f)!!, 0f)
    }
    @Test fun invalidGeometryOrRequestRetainsNative() {
        for (size in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertNull(TileCornerPolicy.radius(72, size))
        for (value in listOf(0, -1, 100)) assertNull(TileCornerPolicy.radius(value, 100f))
    }
    @Test fun excludedContextsRemainNative() {
        fun eligible(enabled: Boolean = true, systemUi: Long? = 202602260L, plugin: Long? = 183022200L,
            display: Int? = 0, card: Boolean = false, detail: Boolean = false,
            theme: Boolean = true, material: Boolean = false) =
            TileCornerPolicy.eligible(enabled, systemUi, plugin, display, card, detail, theme, material)
        assertTrue(eligible())
        assertFalse(eligible(enabled = false)); assertFalse(eligible(systemUi = null))
        assertFalse(eligible(plugin = 1L)); assertFalse(eligible(display = null)); assertFalse(eligible(display = 1))
        assertFalse(eligible(card = true)); assertFalse(eligible(detail = true))
        assertFalse(eligible(theme = false)); assertFalse(eligible(material = true))
    }
    @Test fun newerNativeRadiusAndPerCornerShapeWinOverRestoration() {
        assertTrue(TileCornerPolicy.ownsRadius(72f, false, 72f))
        assertFalse(TileCornerPolicy.ownsRadius(60f, false, 72f))
        assertFalse(TileCornerPolicy.ownsRadius(72f, true, 72f))
        assertFalse(TileCornerPolicy.ownsRadius(Float.NaN, false, 72f))
    }
}
