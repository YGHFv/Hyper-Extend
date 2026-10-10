/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class LockscreenShortcutTargetsTest {
    private fun descriptor(name: String): String = when (name) {
        "void" -> "V"; "boolean" -> "Z"; "float" -> "F"
        else -> "L${name.replace('.', '/')};"
    }
    @Test fun allHooksQueriesAndCallersHaveLiveSignatures() {
        val fixture = javaClass.getResourceAsStream("/lockscreen-shortcut-host-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (spec in LockscreenShortcutTargets.hooks + LockscreenShortcutTargets.queries + LockscreenShortcutTargets.callers) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in fixture)
        }
        assertEquals(5, LockscreenShortcutTargets.hooks.size)
        assertEquals(11, LockscreenShortcutTargets.callers.size)
    }
    @Test fun nativeGestureCleanupAndEntityDataAreNotHookTargets() {
        assertFalse(LockscreenShortcutTargets.hooks.any { it.name in setOf("reset", "onTouchUp", "onTouchEvent", "setOccludedMovedShortcut", "getDrawableWithAvailable") })
        assertFalse(LockscreenShortcutTargets.hooks.any { it.owner == LockscreenShortcutTargets.ENTITY })
        assertTrue(LockscreenShortcutTargets.callers.filter { "lambda" in it.name }.all { it.isStatic })
    }
}
