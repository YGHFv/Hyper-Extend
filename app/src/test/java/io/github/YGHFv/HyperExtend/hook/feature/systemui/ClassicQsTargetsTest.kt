/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class ClassicQsTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/classic-qs-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    @Test fun methodsAndConstructorHaveExactHostEvidence() {
        assertEquals(5, ClassicQsTargets.methods.size)
        for (spec in ClassicQsTargets.methods) {
            assertFalse(spec.isStatic)
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(descriptor(ClassicQsTargets.PANEL) + "-><init>(Landroid/content/Context;Landroid/util/AttributeSet;)V" in signatures)
    }
    @Test fun fieldsAndNativeLayoutBoundariesAreIncluded() {
        for ((owner, name, type) in ClassicQsTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertTrue(descriptor(ClassicQsTargets.QUICK) + "->setTiles(Ljava/util/Collection;)V" in signatures)
        assertTrue("Lcom/android/systemui/qs/MiuiQuickQSPanel\$HeaderTileLayout;->onLayout(ZIIII)V" in signatures)
    }
}
