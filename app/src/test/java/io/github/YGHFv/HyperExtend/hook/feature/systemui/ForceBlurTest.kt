/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class ForceBlurTest {
    @Test fun entryIsOptInAndDoesNotChangeScopeOrSettings() {
        val entry = featureById(ForceBlurSettings.FEATURE)!!
        assertFalse(entry.defaultEnabled)
        assertEquals(listOf("systemui"), entry.scopes)
        assertEquals(FeaturePanel.NEW_CONTROL_CENTER, entry.panel)
        assertFalse(entry.hasDetailPage)
    }
    @Test fun everyRealCapabilityAndDisableGateIsRequired() {
        fun supported(enabled: Boolean = true, blocked: Boolean = false,
            hardware: Boolean = true, disabled: Boolean = false, cross: Boolean = true) =
            ForceBlurPolicy.supported(enabled, blocked, hardware, disabled, cross)
        assertTrue(supported())
        assertFalse(supported(enabled = false)); assertFalse(supported(blocked = true))
        assertFalse(supported(hardware = false)); assertFalse(supported(disabled = true))
        assertFalse(supported(cross = false))
    }
    @Test fun cleanupNeverReintroducesPositiveBlurAndRequiresOwnership() {
        for (radius in listOf(-1, 0, 1, 120, Int.MAX_VALUE)) {
            assertEquals(0, ForceBlurPolicy.radius(radius, true))
            assertEquals(radius, ForceBlurPolicy.radius(radius, false))
        }
        assertTrue(ForceBlurPolicy.owns(120, 120, true))
        assertFalse(ForceBlurPolicy.owns(120, 120, false))
        assertFalse(ForceBlurPolicy.owns(120, 121, true))
        assertFalse(ForceBlurPolicy.owns(0, 0, true))
    }
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"; "long" -> "J"
        else -> if (type.startsWith("[")) type.replace('.', '/') else "L${type.replace('.', '/')};"
    }
    @Test fun capabilityAndNativeCleanupUseCompleteExactSignatures() {
        val signatures = javaClass.getResourceAsStream("/force-blur-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = ForceBlurTargets
        for (method in t.callers + t.framework) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertTrue(descriptor(t.CONSTRUCTOR_OWNER) + "-><init>(" +
            t.constructor.joinToString("") { descriptor(it) } + ")V" in signatures)
        assertEquals(23, signatures.size)
        assertEquals(listOf("android.view.ViewRootImpl", "int"), t.apply.parameters)
        assertEquals(10, t.constructor.size)
        assertFalse(t.framework.any { it.name == "isHighEndGfx" })
    }
}
