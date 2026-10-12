/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.*
import org.junit.Test

class BatteryStylePolicyTest {
    @Test fun trailingMarginFollowsTheActuallyVisibleLastElement() {
        for (present in listOf(false, true)) for (visible in listOf(false, true)) for (hidden in listOf(false, true)) {
            val (number, mark) = BatteryStylePolicy.padding(4, 6, 12, 27, 2f, present, visible, hidden, 3)!!
            assertEquals(4, number.start)
            assertEquals(6, number.end + mark.end)
            assertEquals(if (present && visible && !hidden) 6 else 0, mark.end)
            assertEquals(3, mark.top)
        }
    }
    @Test fun defaultMarkOffsetKeepsNativeBaselineAndMalformedGeometryIsBounded() {
        val (number, mark) = BatteryStylePolicy.padding(200, -1, 200, 27, 2f, true, true, false, 7)!!
        assertEquals(BatteryStylePolicy.Padding(10, 12, 0), number)
        assertEquals(7, mark.top)
        assertEquals(-8, BatteryStylePolicy.padding(0, 0, 12, 0, 2f, true, true, false)!!.second.top)
        assertEquals(18, BatteryStylePolicy.padding(0, 0, 12, 26, 2f, true, true, false)!!.second.top)
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 11f))
            assertNull(BatteryStylePolicy.padding(0, 0, 12, 27, density, true, true, false))
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun refreshFieldsAndDispatchCallersHaveCompleteSignatures() {
        val signatures = javaClass.getResourceAsStream("/battery-style-current-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = BatteryStyleTargets
        for (method in t.refresh + t.callers) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((name, type) in t.fields) assertTrue(descriptor(t.OWNER) + "->" + name + ":" + descriptor(type) in signatures)
        assertEquals(20, signatures.size)
        assertEquals(4, t.refresh.size)
        assertEquals(12, t.callers.size)
        assertEquals(listOf("int"), t.refresh.single { it.name == "onBatteryStyleChanged" }.parameters)
        assertTrue(t.refresh.all { !it.isStatic })
    }
}
