/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.NavigationGeometrySettings
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class NavigationGeometryTest {
    @Test fun frameCannotBeSmallerThanBarAndNativeZeroIsNotInvented() {
        assertEquals(NavigationGeometryPolicy.Geometry(60, 60), NavigationGeometryPolicy.resolve(300, 100, 2f, 20, 50))
        assertEquals(NavigationGeometryPolicy.Geometry(8, 50), NavigationGeometryPolicy.resolve(1, 0, 2f, 20, 50))
        assertEquals(NavigationGeometryPolicy.Geometry(20, 80), NavigationGeometryPolicy.resolve(0, 400, 2f, 20, 50))
        assertNull(NavigationGeometryPolicy.resolve(0, 0, 2f, 20, 50))
        assertNull(NavigationGeometryPolicy.resolve(100, 100, Float.NaN, 20, 50))
        assertNull(NavigationGeometryPolicy.resolve(100, 100, 2f, 0, 50))
        assertNull(NavigationGeometryPolicy.resolve(100, 100, 2f, 50, 20))
    }

    @Test fun allRotationsAndForcedButtonFallbackAreExplicit() {
        assertEquals(false, NavigationGeometryPolicy.landscape(0))
        assertEquals(true, NavigationGeometryPolicy.landscape(1))
        assertEquals(false, NavigationGeometryPolicy.landscape(2))
        assertEquals(true, NavigationGeometryPolicy.landscape(3))
        assertNull(NavigationGeometryPolicy.landscape(-1))
        assertTrue(NavigationGeometryPolicy.eligible(0, 2, false, true))
        assertFalse(NavigationGeometryPolicy.eligible(0, 2, true, true))
        assertFalse(NavigationGeometryPolicy.eligible(0, 0, false, true))
        assertFalse(NavigationGeometryPolicy.eligible(null, 2, false, true))
    }

    @Test fun geometryParametersLiveOnTheMergedNavigationCard() {
        // 几何卡片已并入 navigation_handle_custom（见 core/MergedCardMigration），
        // 四条几何参数仍是同一批键，只是挂到了合并后的卡片上。
        val feature = featureById(io.github.YGHFv.HyperExtend.core.NavigationHandleSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.NAVIGATION, feature.panel)
        assertTrue(feature.config.containsAll(NavigationGeometrySettings.config))
        assertEquals(4, NavigationGeometrySettings.config.map { it.key }.distinct().size)
        assertTrue(NavigationGeometrySettings.config.all { it.default == 0 && it.divisor == 10 })
        assertNull(featureById(NavigationGeometrySettings.FEATURE))
    }

    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun windowMeasurementAndSampleRegionAllHaveCompleteSignatures() {
        val fixtures = javaClass.getResourceAsStream("/navigation-geometry-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in NavigationGeometryTargets.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixtures)
        }
        for ((owner, name, type) in NavigationGeometryTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixtures)
        assertTrue("Landroid/content/res/Resources;->getDimensionPixelSize(I)I" in fixtures)
    }
}
