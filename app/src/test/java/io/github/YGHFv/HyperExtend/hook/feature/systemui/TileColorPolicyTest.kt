/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class TileColorPolicyTest {
    @Test fun onlyOrdinaryActiveStateCanBeColored() {
        assertTrue(TileColorPolicy.eligible(2, 0, false, false, false))
        for (state in listOf(-1, 0, 1, 3)) assertFalse(TileColorPolicy.eligible(state, 0, false, false, false))
        for (color in listOf(-1, 1, 2)) assertFalse(TileColorPolicy.eligible(2, color, false, false, false))
    }
    @Test fun policyAndTransientStatesRetainTheirNativeColors() {
        assertFalse(TileColorPolicy.eligible(2, 0, true, false, false))
        assertFalse(TileColorPolicy.eligible(2, 0, false, true, false))
        assertFalse(TileColorPolicy.eligible(2, 0, false, false, true))
    }
    @Test fun restorationDoesNotOverwriteNewerOrStatefulColors() {
        assertTrue(TileColorPolicy.ownsColor(123, false, 123))
        assertFalse(TileColorPolicy.ownsColor(124, false, 123))
        assertFalse(TileColorPolicy.ownsColor(123, true, 123))
        assertFalse(TileColorPolicy.ownsColor(null, false, 123))
    }
    @Test fun stateFieldsResolveInSystemUiNotThePluginDex() {
        fun descriptor(type: String) = when (type) { "int" -> "I"; "boolean" -> "Z"; else -> "L${type.replace('.', '/')};" }
        val signatures = javaClass.getResourceAsStream("/tile-color-systemui-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for ((name, type) in TileCornerTargets.stateFields)
            assertTrue("instance Lcom/android/systemui/plugins/qs/QSTile\$State;->$name:${descriptor(type)}" in signatures)
    }
}
