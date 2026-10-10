/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class TileCornerTargetsTest {
    private fun descriptor(type: String): String = when (type) {
        "boolean" -> "Z"; "void" -> "V"; "int" -> "I"; "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/tile-corner-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    @Test fun allMethodsMatchHostIncludingStaticBridges() {
        assertEquals(23, TileCornerTargets.methods.size)
        for (spec in TileCornerTargets.methods) {
            val signature = (if (spec.isStatic) "static " else "instance ") + descriptor(spec.owner) + "->" +
                spec.name + "(" + spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
    }
    @Test fun fieldsAndNativeTransitionOwnershipAreExplicit() {
        for ((owner, name, type) in TileCornerTargets.fields)
            assertTrue("instance " + descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertFalse(TileCornerTargets.corner in TileCornerTargets.refresh)
        assertEquals(4, TileCornerTargets.refresh.size)
        assertTrue("instance Lmiui/systemui/controlcenter/qs/tileview/QSTileItemIconView;-><init>(Landroid/content/Context;Landroid/content/Context;ZZ)V" in signatures)
    }
}
