/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.NativeSystemUiSettings
import org.junit.Assert.*
import org.junit.Test

class NativeSystemUiPolicyTest {
    @Test fun exactHostAndDisplayAreRequired() {
        assertTrue(NativeSystemUiPolicy.eligible(true, 202602260, 0, true))
        assertFalse(NativeSystemUiPolicy.eligible(false, 202602260, 0, true))
        assertFalse(NativeSystemUiPolicy.eligible(true, null, 0, true))
        assertFalse(NativeSystemUiPolicy.eligible(true, 202602261, 0, true))
        assertFalse(NativeSystemUiPolicy.eligible(true, 202602260, null, true))
        assertFalse(NativeSystemUiPolicy.eligible(true, 202602260, 1, true))
        assertFalse(NativeSystemUiPolicy.eligible(true, 202602260, 0, false))
    }

    @Test fun scopesAreNestedAndRestoreOnThrow() {
        val scope = NativeExtensionScope()
        val volume = NativeSystemUiPolicy.plugin(NativeSystemUiSettings.VOLUME)!!
        val power = NativeSystemUiPolicy.plugin(NativeSystemUiSettings.POWER)!!
        assertFalse(scope.matches(volume))
        scope.within(NativeSystemUiSettings.VOLUME) {
            assertTrue(scope.matches(volume))
            assertFalse(scope.matches(power))
            assertFalse(scope.matches("com.android.systemui.plugins.qs.QS"))
            runCatching {
                scope.within(NativeSystemUiSettings.POWER) {
                    assertTrue(scope.matches(power))
                    assertFalse(scope.matches(volume))
                    error("native failure")
                }
            }
            assertTrue(scope.matches(volume))
        }
        assertFalse(scope.matches(volume))
        assertFalse(scope.matches(power))
    }

    @Test fun aDifferentThreadNeverInheritsTheRegistrationScope() {
        val scope = NativeExtensionScope()
        val volume = NativeSystemUiPolicy.plugin(NativeSystemUiSettings.VOLUME)!!
        var inherited = true
        scope.within(NativeSystemUiSettings.VOLUME) {
            Thread { inherited = scope.matches(volume) }.apply { start(); join() }
            assertTrue(scope.matches(volume))
        }
        assertFalse(inherited)
    }
}
