/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class LockscreenWallpaperTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "float" -> "F"; "boolean" -> "Z"; "long" -> "J"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/lockscreen-wallpaper-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun exactMethodsAndCallersAreVerifiedInCurrentRom() {
        for (spec in LockscreenWallpaperTargets.methods) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(LockscreenWallpaperTargets.config.isStatic)
        assertTrue(LockscreenWallpaperTargets.caller.isStatic)
        assertEquals(8, LockscreenWallpaperTargets.methods.size)
        assertEquals(listOf("boolean", "float", "int", "boolean", "com.android.keyguard.clock.animation.AnimationTracker"),
            LockscreenWallpaperTargets.animation.parameters)
    }

    @Test fun allReadOnlyFieldsAndExactConstructorAreVerified() {
        for ((field, type) in LockscreenWallpaperTargets.fields) {
            val signature = descriptor(LockscreenWallpaperTargets.PANEL) + "->" + field + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(16, LockscreenWallpaperTargets.fields.size)
        assertTrue(descriptor(LockscreenWallpaperTargets.INTERPOLATE) + "-><init>(I[F)V" in signatures)
    }
}
