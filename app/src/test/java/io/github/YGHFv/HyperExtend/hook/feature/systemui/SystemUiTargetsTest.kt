/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class SystemUiTargetsTest {
    class Fixture {
        fun query(value: String): Boolean = value.isNotBlank()
        fun query(value: Int): Boolean = value != 0
        fun wrongReturn(value: String): String = value
        companion object {
            @JvmStatic fun staticQuery(value: String): Boolean = value.isNotBlank()
        }
    }

    @Test fun exactSignaturesRejectWrongOverloadReturnAndDispatchKind() {
        val methods = Fixture::class.java.declaredMethods
        val target = SystemUiMethod(Fixture::class.java.name, "query", "boolean", listOf("java.lang.String"))
        assertEquals(1, methods.count(target::matches))
        assertEquals(0, methods.count(target.copy(returns = "void")::matches))
        assertEquals(0, methods.count(target.copy(isStatic = true)::matches))
        assertEquals(0, methods.count(target.copy(parameters = emptyList())::matches))
        assertEquals(0, methods.count(target.copy(name = "wrongReturn")::matches))
        assertEquals(1, methods.count(target.copy(name = "staticQuery", isStatic = true)::matches))
    }

    @Test fun staticFilterAndOs4ClassNamesAreExplicit() {
        assertTrue(SystemUiTargets.forceHideOnKeyguard.isStatic)
        assertFalse(SystemUiTargets.alert.isStatic)
        assertEquals(listOf(SystemUiTargets.ENTRY), SystemUiTargets.forceHideOnKeyguard.parameters)
        assertEquals("com.android.systemui.statusbar.ui.viewmodel.KeyguardStatusBarViewModel", SystemUiTargets.KEYGUARD_STATUS_VM)
        assertEquals(SystemUiTargets.all.size, SystemUiTargets.all.toSet().size)
    }

    @Test fun newOs4TargetsDoNotFallBackToRemovedOrUnsafeGlobalMethods() {
        assertEquals("com.android.keyguard.KeyguardPINView", SystemUiTargets.pinInflate.owner)
        assertEquals("com.android.keyguard.stub.MiuiKeyguardUpdateMonitorStub", SystemUiTargets.fingerprintPossible.owner)
        assertEquals(listOf("int"), SystemUiTargets.fingerprintPossible.parameters)
        assertTrue(SystemUiTargets.indicationType.isStatic)
        assertFalse(SystemUiTargets.indicationUpdate.isStatic)
        assertTrue(SystemUiTargets.notificationSettings.isStatic)
        assertEquals("int", SystemUiTargets.focusState.returns)
        assertEquals("int", SystemUiTargets.focusAppState.returns)
        assertEquals(4, SystemUiTargets.foldCallers.size)
        assertTrue(SystemUiTargets.ignoreFold.isStatic)
        assertEquals("run", SystemUiTargets.mediaActionRun.name)
    }
}
