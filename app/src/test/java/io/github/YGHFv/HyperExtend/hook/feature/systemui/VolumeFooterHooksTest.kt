/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class VolumeFooterHooksTest {
    @Test fun suppressionNeverShowsNativeHiddenContent() {
        for (visible in listOf(false, true)) for (expanded in listOf(false, true))
            for (enabled in listOf(false, true)) for (app in listOf(false, true))
                assertEquals(visible && !expanded && enabled && !app,
                    VolumeFooterHooks.suppress(visible, expanded, enabled, app))
    }
    @Test fun restorationOnlyUsesAnOwnedGoneValue() {
        assertEquals(0, VolumeFooterHooks.restoreVisibility(0, 8, false))
        assertNull(VolumeFooterHooks.restoreVisibility(null, 8, false))
        assertNull(VolumeFooterHooks.restoreVisibility(0, 4, false))
        assertNull(VolumeFooterHooks.restoreVisibility(0, 0, false))
        assertNull(VolumeFooterHooks.restoreVisibility(0, 8, true))
    }
    @Test fun exactMethodsAndFieldsHaveReadOnlyHostEvidence() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "boolean" -> "Z"; else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/volume-footer-plugin-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        assertEquals(8, VolumeFooterHooks.methods.size)
        for (spec in VolumeFooterHooks.methods) {
            assertFalse(spec.isStatic)
            val signature = "instance " + descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue("instance Lcom/android/systemui/miui/volume/MiuiVolumeDialogView;->mNeedShowDialog:Z" in signatures)
    }
    @Test fun catalogKeepsExistingOptInKeyAndDocumentsUnbuiltRepair() {
        val feature = featureById(VolumeFooterHooks.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.requirement!!.contains("待真机验收"))
        assertTrue(feature.requirement.contains("独立应用音量面板优先"))
    }
}
