/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class TileMaterialTest {
    @Test fun optInTileEntryUsesSharedSystemUiPluginScope() {
        val entry = featureById(TileMaterialSettings.FEATURE)!!
        assertFalse(entry.defaultEnabled)
        assertEquals(listOf("systemui"), entry.scopes)
        assertEquals(FeaturePanel.TILES, entry.panel)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, entry.group)
        assertFalse(entry.hasDetailPage)
    }
    @Test fun retainsNativeCapabilityAndRestrictedStateGates() {
        fun allow(enabled: Boolean = true, blocked: Boolean = false, host: Long = 202602260,
            plugin: Long = 183022200, display: Int? = 0, classic: Boolean = true,
            material: Boolean = true, theme: Boolean = true, normal: Boolean = true,
            state: Int = 2, color: Int = 0, restricted: Boolean = false, transient: Boolean = false) =
            TileMaterialPolicy.eligible(enabled, blocked, host, plugin, display, classic, material,
                theme, normal, state, color, restricted, transient)
        assertTrue(allow()); assertTrue(allow(state = 1))
        assertFalse(allow(enabled = false)); assertFalse(allow(blocked = true))
        assertFalse(allow(host = 202602261)); assertFalse(allow(plugin = 183022201))
        assertFalse(allow(display = null)); assertFalse(allow(display = 1))
        assertFalse(allow(classic = false)); assertFalse(allow(material = false))
        assertFalse(allow(theme = false)); assertFalse(allow(normal = false))
        assertFalse(allow(state = 0)); assertFalse(allow(state = 3))
        assertFalse(allow(color = 1)); assertFalse(allow(restricted = true)); assertFalse(allow(transient = true))
    }
    @Test fun resourcePairsAreCopiedValidatedAndMustActuallyDiffer() {
        val source = intArrayOf(0xccb0b0b0.toInt(), 100, 0xffe6ff00.toInt(), 106, 0x99ffffff.toInt(), 3)
        val replacement = TileMaterialPolicy.resource(source)!!
        assertTrue(TileMaterialPolicy.changes(intArrayOf(source[0], 0xffebff00.toInt(), source[4]),
            intArrayOf(100, 106, 3), replacement))
        assertFalse(TileMaterialPolicy.changes(replacement.first, replacement.second, replacement))
        assertFalse(TileMaterialPolicy.changes(replacement.first, intArrayOf(100, 105, 3), replacement))
        assertFalse(TileMaterialPolicy.changes(null, replacement.second, replacement))
        source[0] = 0
        assertEquals(0xccb0b0b0.toInt(), replacement.first[0])
        assertNull(TileMaterialPolicy.resource(intArrayOf(1, 100, 2)))
        assertNull(TileMaterialPolicy.resource(intArrayOf(1, 100, 2, 105)))
        assertNull(TileMaterialPolicy.resource(intArrayOf(1, 100, 2, 106, 3, 4)))
    }
    @Test fun takeoverAndSafetyBypassForfeitRestoreOwnership() {
        assertTrue(TileMaterialPolicy.restore(true, false, true, true))
        assertFalse(TileMaterialPolicy.restore(false, false, true, true))
        assertFalse(TileMaterialPolicy.restore(true, true, true, true))
        assertFalse(TileMaterialPolicy.restore(true, false, false, true))
        assertFalse(TileMaterialPolicy.restore(true, false, true, false))
    }
    @Test fun scopesRestoreParentAfterNestedFailureAndAreThreadLocal() {
        val scope = TileMaterialScope<String>()
        scope.within("first") {
            runCatching { scope.within("second") { assertEquals("second", scope.current); error("host") } }
            assertEquals("first", scope.current)
            var other: String? = "unset"
            Thread { other = scope.current }.apply { start(); join() }
            assertNull(other)
        }
        assertNull(scope.current)
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"
        else -> if (type.startsWith("[")) type.replace('.', '/') else "L${type.replace('.', '/')};"
    }
    @Test fun fullSignaturesIncludeRealRenderingAndAllDispatchCallers() {
        val signatures = javaClass.getResourceAsStream("/tile-material-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = TileMaterialTargets
        for (method in t.methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertTrue("Lmiuix/theme/token/ColorBlendToken;-><init>()V" in signatures)
        assertEquals(76, signatures.size)
        assertEquals(43, t.callers.size)
        assertTrue(t.renders.all { it.name == "setMiBackgroundBlendColors" && it.isStatic })
        // updateBlendBlur 只是原生调用点（已列入 callers 一并反优化），不是被替换的渲染入口。
        assertFalse(t.renders.any { it.name == "updateBlendBlur" })
        assertFalse(t.writers.any { it.name == "updateBlendBlur" })
    }
}
