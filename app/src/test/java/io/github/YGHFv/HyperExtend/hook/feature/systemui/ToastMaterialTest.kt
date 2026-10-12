/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class ToastMaterialTest {
    @Test fun entryIsOptInSystemUiOnlyAndDoesNotChangePluginScope() {
        val entry = featureById(ToastMaterialSettings.FEATURE)!!
        assertFalse(entry.defaultEnabled)
        assertEquals(listOf("systemui"), entry.scopes)
        assertEquals(FeaturePanel.TOAST, entry.panel)
        assertFalse(entry.hasDetailPage)
    }
    @Test fun nativeBlurAndEveryUnsafeSceneKeepNative() {
        fun eligible(enabled: Boolean = true, blocked: Boolean = false, attached: Boolean = true,
            shown: Boolean = true, main: Boolean = true, interactive: Boolean = true,
            locked: Boolean = false, nativeBlur: Boolean = false, now: Long = 1001) =
            ToastMaterialPolicy.eligible(enabled, blocked, attached, shown, main, interactive, locked, nativeBlur, now, 1000)
        assertTrue(eligible())
        assertFalse(eligible(enabled = false)); assertFalse(eligible(blocked = true))
        assertFalse(eligible(attached = false)); assertFalse(eligible(shown = false))
        assertFalse(eligible(main = false)); assertFalse(eligible(interactive = false))
        assertFalse(eligible(locked = true)); assertFalse(eligible(nativeBlur = true))
        assertFalse(eligible(now = 31_000)); assertFalse(eligible(now = 999))
    }
    @Test fun geometryAndContrastAreBoundedWithoutChangingText() {
        assertEquals(24f, ToastMaterialPolicy.radius(200, 80, 1f)!!, 0f)
        assertEquals(15f, ToastMaterialPolicy.radius(200, 30, 3f)!!, 0f)
        assertNull(ToastMaterialPolicy.radius(0, 80, 1f))
        assertNull(ToastMaterialPolicy.radius(200, 80, Float.NaN))
        assertEquals(0xde202020.toInt(), ToastMaterialPolicy.tint(0xffffffff.toInt()))
        assertEquals(0xdefafafa.toInt(), ToastMaterialPolicy.tint(0xff101010.toInt()))
    }
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun creationAndResourceTagAreExactNotOldStubNameMatches() {
        val signatures = javaClass.getResourceAsStream("/toast-material-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = ToastMaterialTargets
        for (method in listOf(t.create, t.nativeBlur) + t.callers) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(descriptor(t.TOAST) + "-><init>(" + t.constructor.joinToString("") { descriptor(it) } + ")V" in signatures)
        for ((owner, name, type) in t.fields) assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertEquals(7, signatures.size)
    }
}
