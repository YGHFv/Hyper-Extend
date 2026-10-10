/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationImportanceIconScopeTest {
    private val scope = NotificationImportanceIconScope()
    @Test fun onlyLowestKnownLevelsSuppressStatusBarIcon() {
        val entry = Any()
        scope.withEntry(entry) {
            for (level in listOf(null, -1000, -1, 0, 1, 2, 3, 4, 5)) {
                assertEquals(level in 0..1, scope.suppress(entry, 32, level))
            }
        }
    }
    @Test fun otherEffectsAndUnscopedCallsRemainNative() {
        val entry = Any()
        assertFalse(scope.suppress(entry, 32, 1))
        scope.withEntry(entry) {
            for (effect in listOf(null, 0, 1, 2, 4, 8, 16, 64, 128, 256, 32 or 16)) {
                assertFalse(scope.suppress(entry, effect, 1))
            }
            assertFalse(scope.suppress(Any(), 32, 1))
            assertFalse(scope.suppress(null, 32, 1))
        }
    }
    @Test fun nestedIdentityAndExceptionsRestoreOuterScope() {
        val outer = Any(); val inner = Any()
        scope.withEntry(outer) {
            assertTrue(runCatching { scope.withEntry(inner) {
                assertFalse(scope.suppress(outer, 32, 1))
                assertTrue(scope.suppress(inner, 32, 1))
                error("host failure")
            } }.isFailure)
            assertTrue(scope.suppress(outer, 32, 1))
        }
        assertFalse(scope.suppress(outer, 32, 1))
    }
    @Test fun aDifferentThreadNeverInheritsTheModelContext() {
        val entry = Any()
        scope.withEntry(entry) {
            var suppressed = true
            Thread { suppressed = scope.suppress(entry, 32, 1) }.apply { start(); join() }
            assertFalse(suppressed)
            assertTrue(scope.suppress(entry, 32, 1))
        }
    }
    @Test fun hostRunsOnceAndItsResultIsPreserved() {
        var calls = 0
        assertEquals(7, scope.withEntry(Any()) { calls++; 7 })
        assertEquals(1, calls)
    }
    @Test fun targetsUseExactLiveModelAndCallerSignatures() {
        fun descriptor(name: String) = when (name) { "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; else -> "L${name.replace('.', '/')};" }
        val fixture = javaClass.getResourceAsStream("/notification-importance-icon-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        val targets = NotificationImportanceIconTargets
        for (spec in targets.callers + targets.model + targets.suppress) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" + spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in fixture)
            assertFalse(spec.isStatic)
        }
    }
}
