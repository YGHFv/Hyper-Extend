/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class PercentageTest {
    @Test fun entryAndBothSourcesAreOptInWithPersistedConfiguration() {
        val feature = featureById(PercentageSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.NEW_CONTROL_CENTER, feature.panel)
        assertTrue(feature.options.all { !it.defaultEnabled })
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        assertEquals(2, PercentageSettings.style(""))
        assertEquals(1, PercentageSettings.style("1"))
        assertEquals(54, PercentageSettings.top("bad"))
        assertEquals(1024, PercentageSettings.top("9999"))
    }
    @Test fun placementHonorsSafeInsetsAndActualWindowNotDisplaySize() {
        val normal = PercentagePolicy.place(400, 800, 100, 80, 0, 30, 0, 40, 54, 2f, false)!!
        assertEquals(PercentagePolicy.Placement(150, 108), normal)
        val landscape = PercentagePolicy.place(800, 400, 100, 80, 40, 20, 0, 20, 54, 2f, true)!!
        assertEquals(PercentagePolicy.Placement(370, 76), landscape)
        val clamped = PercentagePolicy.place(400, 300, 100, 80, 0, 30, 0, 40, 1024, 3f, false)!!
        assertEquals(180, clamped.top)
    }
    @Test fun unavailableSpaceAndInvalidGeometryKeepNative() {
        assertNull(PercentagePolicy.place(80, 80, 100, 40, 0, 0, 0, 0, 54, 2f, false))
        assertNull(PercentagePolicy.place(400, 80, 100, 80, 0, 30, 0, 40, 54, 2f, false))
        assertNull(PercentagePolicy.place(400, 800, 100, 80, 0, 0, 0, 0, 54, Float.NaN, false))
    }
    @Test fun staleHiddenLockedAndAccessibilitySessionsMustStop() {
        assertTrue(PercentagePolicy.active(1001, 1000, true, true, true, true, false, false, false))
        assertFalse(PercentagePolicy.active(31_000, 1000, true, true, true, true, false, false, false))
        assertFalse(PercentagePolicy.active(1001, 1000, true, false, true, true, false, false, false))
        assertFalse(PercentagePolicy.active(1001, 1000, true, true, true, true, true, false, false))
        assertFalse(PercentagePolicy.active(1001, 1000, true, true, true, true, false, true, false))
        assertFalse(PercentagePolicy.active(1001, 1000, true, true, true, true, false, false, true))
    }
    @Test fun hardwarePanelUsesDiscreteLevelsAndRejectsExtendedOrUnknownRanges() {
        assertEquals("46%", PercentagePolicy.panelVolume(7, 0, 15, false, 15_000, 7000))
        assertEquals("0%", PercentagePolicy.panelVolume(7, 0, 15, true, 15_000, 7000))
        assertEquals("100%", PercentagePolicy.panelVolume(15, 1, 15, false, 15_000, 15_000))
        assertNull(PercentagePolicy.panelVolume(15, 0, 15, false, 16_000, 16_000))
        assertNull(PercentagePolicy.panelVolume(16, 0, 15, false, 15_000, 15_000))
        assertNull(PercentagePolicy.panelVolume(1, 0, Int.MAX_VALUE, false, Int.MAX_VALUE, 1))
        assertNull(PercentagePolicy.panelVolume(1, 0, 0, false, 0, 0))
    }
    @Test fun legacyListenerAndPanelHooksHaveExactCurrentHostEvidence() {
        val signatures = javaClass.getResourceAsStream("/percentage-legacy-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = PercentageLegacyTargets
        for (method in t.classic + t.classicCallers + t.volume + t.queries + t.volumeCallers) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in t.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(47, signatures.size)
        assertEquals(3, t.classic.size)
        assertEquals(6, t.volume.size)
    }
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun allListenerDispatchAndVolumeMembersHaveCompleteSignatures() {
        val signatures = javaClass.getResourceAsStream("/percentage-host-members.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (method in PercentageTargets.methods + PercentageTargets.callers + PercentageTargets.queries) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        for ((owner, name, type) in PercentageTargets.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in signatures)
        }
        assertEquals(12, PercentageTargets.methods.size)
        assertEquals(22, PercentageTargets.callers.size)
        assertEquals(35, signatures.size)
    }
}
