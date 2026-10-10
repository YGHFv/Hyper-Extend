/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class AutoCollapseHooksTest {
    @Test fun onlyKnownUsableStatesAreEligible() {
        for (state in listOf(1, 2)) assertTrue(AutoCollapseHooks.eligible(state, false, false, "rotation", false, 0))
        for (state in listOf(-1, 0, 3)) assertFalse(AutoCollapseHooks.eligible(state, false, false, "rotation", false, 0))
    }
    @Test fun policyTransientKeyguardDetailAndEditKeepPanel() {
        assertFalse(AutoCollapseHooks.eligible(2, true, false, "rotation", false, 0))
        assertFalse(AutoCollapseHooks.eligible(2, false, true, "rotation", false, 0))
        assertFalse(AutoCollapseHooks.eligible(2, false, false, "rotation", true, 0))
        for (state in listOf(-1, 1, 2)) assertFalse(AutoCollapseHooks.eligible(2, false, false, "rotation", false, state))
        for (spec in listOf(null, "", "edit")) assertFalse(AutoCollapseHooks.eligible(2, false, false, spec, false, 0))
    }
    @Test fun exactSignaturesAndStaticBridgeMatchHostFixture() {
        fun descriptor(type: String) = when (type) {
            "boolean" -> "Z"; "int" -> "I"; "void" -> "V"; else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/auto-collapse-systemui-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(11, AutoCollapseHooks.methods.size)
        for (spec in AutoCollapseHooks.methods) {
            val signature = (if (spec.isStatic) "static " else "instance ") + descriptor(spec.owner) + "->" +
                spec.name + "(" + spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in AutoCollapseHooks.fields)
            assertTrue("instance " + descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
    }
    @Test fun catalogDoesNotEquateClickDispatchWithSuccess() {
        val feature = featureById(AutoCollapseHooks.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.requirement!!.contains("不代表磁贴操作成功"))
        assertTrue(feature.requirement.contains("待真机验收"))
        assertTrue(feature.requirement.contains("当前插件调用链未确认覆盖"))
    }
}
