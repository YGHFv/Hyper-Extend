/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class PluginAppearanceTest {
    @Test fun footerNeverOverridesHostSuppression() {
        assertFalse(collapsedFooterVisible(false, false))
        assertFalse(collapsedFooterVisible(false, true))
        assertFalse(collapsedFooterVisible(true, false))
        assertTrue(collapsedFooterVisible(true, true))
    }

    @Test fun pluginFeaturesAreOptInAndExplicitlyAwaitDeviceAcceptance() {
        for (id in listOf("control_center_hide_edit", "volume_hide_collapsed_footer")) {
            val feature = featureById(id)!!
            assertFalse(feature.defaultEnabled)
            assertEquals(listOf("systemui"), feature.scopes)
            assertTrue(feature.requirement!!.contains("待真机验收"))
        }
    }
}
