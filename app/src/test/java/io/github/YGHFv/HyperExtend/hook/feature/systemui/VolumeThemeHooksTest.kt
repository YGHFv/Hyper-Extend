/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class VolumeThemeHooksTest {
    @Test fun queryRequiresEveryGateAndPreservesNativeTrue() {
        for (scoped in listOf(false, true)) for (enabled in listOf(false, true)) for (ready in listOf(false, true)) {
            assertEquals(scoped && enabled && ready, VolumeThemeHooks.result(false, scoped, enabled, ready))
            assertTrue(VolumeThemeHooks.result(true, scoped, enabled, ready))
        }
    }
    @Test fun nestedExclusionAndExceptionRestoreScope() {
        val scope = VolumeThemeScope()
        assertFalse(scope.active)
        scope.within(true) {
            assertTrue(scope.active)
            runCatching { scope.within(false) { assertFalse(scope.active); error("native failure") } }
            assertTrue(scope.active)
        }
        assertFalse(scope.active)
    }
    @Test fun scopeDoesNotLeakAcrossThreads() {
        val scope = VolumeThemeScope()
        var other: Boolean? = null
        scope.within(true) {
            Thread { other = scope.active }.also { it.start(); it.join() }
            assertTrue(scope.active)
        }
        assertEquals(false, other)
    }
    @Test fun exactTargetsRetainStaticSliderAndAuditedCallers() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
            else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/volume-theme-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(7, VolumeThemeHooks.methods.size)
        assertEquals(3, VolumeThemeHooks.callers.size)
        for (spec in VolumeThemeHooks.methods) {
            val signature = (if (spec.isStatic) "static " else "instance ") + descriptor(spec.owner) + "->" +
                spec.name + "(" + spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
    }
    @Test fun catalogDoesNotClaimFullSkinReplacementOrExecutedTests() {
        val feature = featureById(VolumeThemeHooks.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.requirement!!.contains("不是完整替换"))
        assertTrue(feature.requirement.contains("待真机验收"))
    }
}
