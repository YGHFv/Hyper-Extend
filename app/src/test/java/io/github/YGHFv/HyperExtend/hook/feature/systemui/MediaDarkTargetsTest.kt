/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MediaDarkTargetsTest {
    private fun descriptor(name: String): String = when (name) {
        "void" -> "V"
        "boolean" -> "Z"
        else -> "L${name.replace('.', '/')};"
    }

    @Test fun everyInstalledTargetAndDeoptimizedCallerHasAnExactHostSignature() {
        val recorded = javaClass.getResourceAsStream("/media-dark-host-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        val methods = MediaDarkTargets.effects + MediaDarkTargets.typedBlur + MediaDarkTargets.foreground + MediaDarkTargets.callers
        for (method in methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue("Missing MT signature: $signature", signature in recorded)
            assertFalse(method.isStatic)
        }
    }

    @Test fun aodListenerIsNotConfusedWithControllerVisibilityCallback() {
        val callback = MediaDarkTargets.callers.single { it.owner.contains("mediaFullAodListener") }
        assertEquals("onFullAodChange", callback.name)
        assertEquals(listOf("boolean", "boolean"), callback.parameters)
        assertTrue(MediaDarkTargets.effects.none { it.name == "clear" })
    }

    @Test fun allSevenEffectsAndTypedBlurAreDistinctFromDeleteMenuAndIslandEffects() {
        assertEquals(7, MediaDarkTargets.effects.size)
        val all = MediaDarkTargets.effects + MediaDarkTargets.typedBlur
        assertEquals(8, all.toSet().size)
        assertTrue(all.none { it.owner.contains("DeleteMenu") || it.owner.contains("Island") })
        assertEquals(MediaDarkTargets.HEADER, MediaDarkTargets.typedBlur.parameters.first())
    }

    @Test fun artworkLifecycleAndClearSignaturesMatchReadOnlyHostEvidence() {
        val recorded = javaClass.getResourceAsStream("/media-dark-host-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        val methods = MediaDarkTargets.artworkCallers + MediaDarkTargets.bind + MediaDarkTargets.detach + MediaDarkTargets.replaceHolder +
            MediaDarkTargets.effects.map { it.copy(name = "clear", parameters = listOf("java.lang.Object")) }
        for (method in methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue("Missing MT signature: $signature", signature in recorded)
        }
        assertTrue(MediaDarkTargets.artworkCallers.single { it.name.startsWith("access") }.isStatic)
    }
}
