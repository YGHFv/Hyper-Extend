/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class LockscreenChargingTargetsTest {
    private fun descriptor(name: String) = if (name == "void") "V" else "L${name.replace('.', '/')};"
    @Test fun installAndAllThreeSetAreaCallersHaveExactLiveSignatures() {
        val signatures = javaClass.getResourceAsStream("/lockscreen-charging-host-methods.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (spec in LockscreenChargingTargets.callers + LockscreenChargingTargets.area) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(3, LockscreenChargingTargets.callers.size)
        assertTrue(LockscreenChargingTargets.callers.single { it.name == "bind" }.isStatic)
    }
}
