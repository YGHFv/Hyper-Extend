/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class ShadeHeaderBlurTest {
    @Test fun existingGradientIsNeverRemovedAndUnsupportedDevicesStayNative() {
        assertTrue(ShadeHeaderBlurPolicy.useGradient(true, false, null, false))
        assertTrue(ShadeHeaderBlurPolicy.useGradient(false, true, 0, true))
        assertFalse(ShadeHeaderBlurPolicy.useGradient(false, false, 0, true))
        assertFalse(ShadeHeaderBlurPolicy.useGradient(false, true, 0, false))
        assertFalse(ShadeHeaderBlurPolicy.useGradient(false, true, null, true))
        assertFalse(ShadeHeaderBlurPolicy.useGradient(false, true, 1, true))
    }

    @Test fun allSevenCallerSignaturesAndSelectorMatchEvidence() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"; "long" -> "J"
            else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/shade-header-gradient-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(7, ShadeHeaderBlurTargets.callers.size)
        for (method in ShadeHeaderBlurTargets.callers + ShadeHeaderBlurTargets.query) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
    }
}
