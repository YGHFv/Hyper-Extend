/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.NotificationBlendSettings
import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class NotificationBlendTest {
    @Test fun colorsCopyOnlyColorSlotsForBothThemes() {
        val native = intArrayOf(1, 2, 3, 4, 5, 6)
        val day = NotificationBlendPolicy.colors(native, NotificationBlendScene.SHADE, false)!!
        val night = NotificationBlendPolicy.colors(native, NotificationBlendScene.SHADE, true)!!
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), native)
        assertArrayEquals(intArrayOf(0xff818181.toInt(), 2, 0xffb3ff00.toInt(), 4, 0x00ffffff, 6), day)
        assertArrayEquals(intArrayOf(0xff737373.toInt(), 2, 0xff128f00.toInt(), 4, 0, 6), night)
        assertNull(NotificationBlendPolicy.colors(intArrayOf(1, 2, 3), NotificationBlendScene.SHADE, false))
    }

    @Test fun advancedAndExcludedScenesNeverBypassMainGates() {
        fun allowed(scene: NotificationBlendScene, advanced: Boolean = false) =
            NotificationBlendPolicy.eligible(scene, true, advanced, 202602260, 0, true)
        assertTrue(allowed(NotificationBlendScene.SHADE))
        assertFalse(allowed(NotificationBlendScene.KEYGUARD))
        assertTrue(allowed(NotificationBlendScene.KEYGUARD, true))
        assertFalse(allowed(NotificationBlendScene.EXCLUDED, true))
        assertFalse(NotificationBlendPolicy.eligible(NotificationBlendScene.SHADE, true, true, 202602261, 0, true))
        assertFalse(NotificationBlendPolicy.eligible(NotificationBlendScene.SHADE, true, true, 202602260, 1, true))
        assertFalse(NotificationBlendPolicy.eligible(NotificationBlendScene.SHADE, false, true, 202602260, 0, true))
        val feature = featureById(NotificationBlendSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.options.single().defaultEnabled)
    }

    @Test fun nativeSinglePairAndOuterAodFallbackRemainBounded() {
        assertArrayEquals(intArrayOf(0xff919191.toInt(), 42),
            NotificationBlendPolicy.colors(intArrayOf(12, 42), NotificationBlendScene.HEADS_UP, false))
        assertNull(NotificationBlendPolicy.colors(intArrayOf(12, 42), NotificationBlendScene.EXCLUDED, false))
        val scope = NotificationBlendScope<NotificationBlendScene>()
        scope.within(NotificationBlendScene.EXCLUDED) {
            runCatching { scope.within(NotificationBlendScene.SHADE) {
                assertEquals(NotificationBlendScene.EXCLUDED, scope.current)
                error("native failure")
            } }
            assertEquals(NotificationBlendScene.EXCLUDED, scope.current)
        }
        assertNull(scope.current)
    }

    private fun descriptor(type: String) = when {
        type == "void" -> "V"
        type == "boolean" -> "Z"
        type == "int" -> "I"
        type.startsWith("[") -> type.replace('.', '/')
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun allScopesAndDispatchCallersHaveCompleteFixtures() {
        val fixtures = javaClass.getResourceAsStream("/notification-blend-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in NotificationBlendTargets.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixtures)
        }
        assertEquals(27, NotificationBlendTargets.scopes.size)
        assertEquals(7, NotificationBlendTargets.callers.size)
        assertEquals(3, NotificationBlendTargets.scopes.values.count { it == NotificationBlendScene.EXCLUDED })
    }
}
