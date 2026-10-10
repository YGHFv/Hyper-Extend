/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class SliderValueTargetsTest {
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private val signatures get() = javaClass.getResourceAsStream("/slider-value-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun kotlinStaticBridgesMatchHostModifiers() {
        val staticSignatures = javaClass.getResourceAsStream("/slider-value-host-static-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(4, staticSignatures.size)
        for (owner in listOf(SliderValueTargets.BRIGHTNESS, SliderValueTargets.VOLUME)) {
            for (spec in SliderValueTargets.methods(owner) + SliderValueTargets.callers(owner)) {
                val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                    spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
                assertEquals(signature, signature in staticSignatures, spec.isStatic)
            }
        }
    }

    @Test fun exactMethodsAreRecordedForBothIndependentAdapters() {
        for (owner in listOf(SliderValueTargets.BRIGHTNESS, SliderValueTargets.VOLUME)) {
            for (spec in SliderValueTargets.methods(owner) + SliderValueTargets.callers(owner)) {
                val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                    spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
                assertTrue(signature, signature in signatures)
            }
        }
    }
    @Test fun allFieldTypesAreRecorded() {
        for (owner in listOf(SliderValueTargets.BRIGHTNESS, SliderValueTargets.VOLUME)) {
            for ((type, name, expected) in SliderValueTargets.fields(owner)) {
                val signature = descriptor(type) + "->" + name + ":" + descriptor(expected)
                assertTrue(signature, signature in signatures)
            }
        }
    }
}
