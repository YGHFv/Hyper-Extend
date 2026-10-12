/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class CardTileColorTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/card-tile-color-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun everyMethodAndFieldHasExactPluginOrSharedHostEvidence() {
        for (method in CardTileColorTargets.methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in CardTileColorTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
    }

    @Test fun nativeWarningMaterialAndRefreshPathsAreRequired() {
        val t = CardTileColorTargets
        assertTrue(t.methods.containsAll(listOf(t.warning, t.restricted, t.blend, t.recycle)))
        assertTrue(t.refresh.containsAll(listOf(t.update, t.material, t.resources, t.background)))
        assertEquals("void", t.enabled.returns)
        assertEquals(listOf("android.graphics.drawable.Drawable"), t.enabled.parameters)
    }
}
