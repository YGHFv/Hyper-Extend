/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class DimTileIconTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/dim-tile-icon-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun updateAndAllCallerSignaturesMatchCurrentHost() {
        assertEquals(4, DimTileIconTargets.methods.size)
        for (spec in DimTileIconTargets.methods) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
            assertFalse(spec.isStatic)
        }
    }

    @Test fun contextSpecAndStateFieldsAreVerified() {
        assertEquals(5, DimTileIconTargets.fields.size)
        for ((owner, name, type) in DimTileIconTargets.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
    }

    @Test fun plainDrawableIconConstructorAndIdentityEqualityAreVerified() {
        val icon = descriptor(DimTileIconTargets.DRAWABLE_ICON)
        assertTrue(icon + "-><init>(Landroid/graphics/drawable/Drawable;)V" in signatures)
        assertTrue(icon + "->equals(Ljava/lang/Object;)Z" in signatures)
        assertTrue(icon + "->getDrawable(Landroid/content/Context;)Landroid/graphics/drawable/Drawable;" in signatures)
        assertTrue(icon + "->getInvisibleDrawable(Landroid/content/Context;)Landroid/graphics/drawable/Drawable;" in signatures)
    }
}
