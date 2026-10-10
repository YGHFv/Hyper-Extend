/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class StockTilesTargetsTest {
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/stock-tiles-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun exactEditorAndCallerSignaturesAreVerified() {
        val methods = StockTilesTargets.classicMethods + StockTilesTargets.pluginMethods
        assertEquals(8, methods.size)
        for (spec in methods) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(StockTilesTargets.pluginCallers.take(2).all { it.isStatic })
    }

    @Test fun allContextAndHelperFieldsAreVerified() {
        for ((owner, field, type) in StockTilesTargets.classicFields + StockTilesTargets.pluginFields) {
            val signature = descriptor(owner) + "->" + field + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
    }

    @Test fun candidateProviderMappingIsCompleteAndVerified() {
        assertEquals(StockTilesPolicy.candidates.toSet(), StockTilesTargets.providers.keys)
        for (provider in StockTilesTargets.providers.values) {
            assertTrue(descriptor(StockTilesTargets.FACTORY) + "->" + provider + ":Ljavax/inject/Provider;" in signatures)
        }
    }
}
