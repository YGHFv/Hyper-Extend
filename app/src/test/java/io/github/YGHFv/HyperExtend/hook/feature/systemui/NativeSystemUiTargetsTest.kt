/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NativeSystemUiTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/native-systemui-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun selectionAndNativeFallbackHaveExactEvidence() {
        val t = NativeSystemUiTargets
        for (method in t.methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(descriptor(t.VOLUME) + "-><init>(" +
            t.volumeConstructor.joinToString("") { descriptor(it) } + ")V" in signatures)
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertTrue(signatures.any { it.contains("VolumeDialogComponent\$\$ExternalSyntheticLambda1;->accept") })
        assertTrue(signatures.any { it.contains("GlobalActionsComponent\$\$ExternalSyntheticLambda0;->get") })
    }
}
