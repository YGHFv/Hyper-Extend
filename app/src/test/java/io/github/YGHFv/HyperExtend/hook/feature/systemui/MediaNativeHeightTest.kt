/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaNativeHeightTest {
    @Test fun drawingScopeIsIdentityBoundNestedAndExceptionSafe() {
        val scope = MediaNativeHeightScope()
        val a = Any(); val b = Any()
        assertNull(scope.height(a))
        scope.withHeight(a, 8) {
            assertEquals(8, scope.height(a)); assertNull(scope.height(b))
            runCatching { scope.withHeight(b, 12) {
                assertNull(scope.height(a)); assertEquals(12, scope.height(b))
                error("native draw failed")
            } }
            assertEquals(8, scope.height(a)); assertNull(scope.height(b))
        }
        assertNull(scope.height(a)); assertNull(scope.height(b))
    }
    @Test fun lowEndHeightStaysEvenAndWithinActualAvailableContent() {
        assertEquals(6, MediaProgressPolicy.height(160, 3f, 7))
        assertNull(MediaProgressPolicy.height(60, 2f, 1))
        assertNull(MediaProgressPolicy.height(60, Float.NaN, 20))
    }
    @Test fun completeNativeDrawAndFrameworkGetterAreNotGuessedOverloads() {
        val members = javaClass.getResourceAsStream("/media-native-height-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(setOf(
            "Landroid/widget/ProgressBar;->getMaxHeight()I",
            "Lmiuix/miuixbasewidget/widget/HyperProgressSeekBar;->drawPrimaryMode(Landroid/graphics/Canvas;)V",
        ), members)
        assertEquals("android.widget.ProgressBar", MediaNativeHeightHooks.getter.owner)
        assertTrue(MediaNativeHeightHooks.getter.parameters.isEmpty())
        assertEquals(listOf("android.graphics.Canvas"), MediaNativeHeightHooks.primary.parameters)
    }
}
