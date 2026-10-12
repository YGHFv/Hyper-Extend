/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.WindowDecorationSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class WindowDecorationTest {
    @Test fun fourIndependentOptInControlsReachWindowPanel() {
        for (id in listOf(WindowDecorationSettings.HIDE_DOT, WindowDecorationSettings.DISABLE_MENU,
            WindowDecorationSettings.DISABLE_INFINITY, WindowDecorationSettings.HIDE_BOTTOM)) {
            val feature = featureById(id)!!
            assertFalse(feature.defaultEnabled)
            assertFalse(feature.hasDetailPage)
            assertEquals(FeaturePanel.WINDOWS, feature.panel)
            assertEquals(listOf("systemui"), feature.scopes)
        }
    }
    @Test fun bottomDrawingOnlySuppressesFreeform() {
        for (mode in listOf(null, 0, 1, 2, 3, 4, 6)) assertFalse(WindowDecorationPolicy.hideBottom(true, mode))
        assertTrue(WindowDecorationPolicy.hideBottom(true, 5))
        assertFalse(WindowDecorationPolicy.hideBottom(false, 5))
    }
    @Test fun infinityKeepsNativeFalseAndNonGestureCallers() {
        assertFalse(WindowDecorationPolicy.infinityResult(false, false, false))
        assertTrue(WindowDecorationPolicy.infinityResult(true, true, false))
        assertTrue(WindowDecorationPolicy.infinityResult(true, false, true))
        assertFalse(WindowDecorationPolicy.infinityResult(true, true, true))
    }
    @Test fun gestureScopeIsNestedExceptionSafeAndThreadLocal() {
        val scope = WindowGestureScope()
        assertFalse(scope.active)
        scope.within {
            assertTrue(scope.active)
            runCatching { scope.within { error("host error") } }
            assertTrue(scope.active)
            var other = true
            Thread { other = scope.active }.apply { start(); join() }
            assertFalse(other)
        }
        assertFalse(scope.active)
    }
    @Test fun lazySingletonInitializationInsideGestureKeepsNativeCapability() {
        val t = WindowDecorationTargets
        val event = t.event.owner to t.event.name
        val query = t.infinity.owner to t.infinity.name
        assertTrue(WindowDecorationPolicy.directGestureCaller(listOf(query, event)))
        assertFalse(WindowDecorationPolicy.directGestureCaller(listOf(query,
            "com.android.wm.shell.multitasking.stubs.infinitymode.MiuiInfinityModeStub" to "<clinit>", event)))
        assertFalse(WindowDecorationPolicy.directGestureCaller(emptyList()))
        assertFalse(WindowDecorationPolicy.directGestureCaller(listOf(query, "com.android.wm.shell.Other" to "query", event)))
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun everyTargetAndCallerHasCompleteCurrentLibraryEvidence() {
        val fixture = javaClass.getResourceAsStream("/window-decoration-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = WindowDecorationTargets
        for (method in t.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixture)
        }
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixture)
        assertEquals(20, fixture.size)
        assertTrue(t.infinity.isStatic)
        assertTrue(t.freeformHeight.isStatic)
        assertFalse(t.menu.isStatic)
        assertEquals(listOf("int", "java.lang.String"), t.menu.parameters)
        assertTrue(t.methods.none { it.name == "<clinit>" })
    }
}
