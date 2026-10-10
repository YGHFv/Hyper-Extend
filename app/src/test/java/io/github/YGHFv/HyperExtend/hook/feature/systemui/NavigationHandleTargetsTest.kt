/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NavigationHandleTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "float" -> "F"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/navigation-handle-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun everyHookAndDeoptimizedCallerHasExactLiveDexSignature() {
        for (spec in NavigationHandleTargets.entries + NavigationHandleTargets.callers) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
            assertFalse(spec.isStatic)
        }
        assertEquals(4, NavigationHandleTargets.entries.size)
        assertEquals(2, NavigationHandleTargets.callers.size)
    }

    @Test fun allFieldNamesAndTypesAreVerifiedNotGuessed() {
        for ((field, type) in NavigationHandleTargets.fields) {
            val signature = descriptor(NavigationHandleTargets.HANDLE) + "->" + field + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(8, NavigationHandleTargets.fields.size)
        assertEquals(24, signatures.size)
    }
}
