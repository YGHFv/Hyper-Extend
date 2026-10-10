/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class FeaturePresentationTest {
    private val simple get() = featureById("rotation_lock_fix")!!

    @Test fun standaloneSwitchesDoNotNeedAnExtraPage() {
        assertEquals(
            setOf(
                "screenshot_clipboard", "milink_clipboard_guard", "mishare_receive_guard",
                "rotation_suggestion", "rotation_lock_fix", "status_bar_double_tap", "status_bar_screenshot_hide",
            ) + (SYSTEM_UI_FEATURES + APP_VOLUME_FEATURE).filterNot { it.hasDetailPage }.map { it.id },
            FEATURES.filterNot { it.hasDetailPage }.map { it.id }.toSet(),
        )
    }

    @Test fun eachAdditionalControlKeepsItsDetailPage() {
        assertTrue(simple.copy(options = listOf(HyperOption("test.option", "Option"))).hasDetailPage)
        assertTrue(simple.copy(config = listOf(HyperText("test.text", "Text"))).hasDetailPage)
        assertTrue(simple.copy(configKey = "test.image").hasDetailPage)
        assertTrue(simple.copy(extra = FeatureExtra.MONET_SCHEME).hasDetailPage)
    }

    @Test fun requirementsDoNotForceASingleSwitchIntoADetailPage() {
        assertFalse(simple.hasDetailPage)
        assertEquals("需重启设备生效", simple.requirement)
        assertFalse(featureById("mishare_receive_guard")!!.hasDetailPage)
        assertTrue(featureById("mishare_receive_guard")!!.requirement!!.contains("接收端"))
    }

    @Test fun groupedSwitchesKeepTheirParentAndHosts() {
        val feature = featureById("status_bar_screenshot_hide")!!
        assertFalse(feature.hasDetailPage)
        assertEquals(ScopeFeatureGroup.STATUS_BAR, feature.entryGroup)
        assertEquals(listOf("systemui", "screenshot"), feature.scopes)
        assertTrue(featureById("native_notify_icon")!!.hasDetailPage)
        assertTrue(featureById("nfc_card_face")!!.hasDetailPage)
    }

    @Test fun searchingPreservesInlineAndDetailClassification() {
        assertFalse(searchFeatures(simple.title).single().feature.hasDetailPage)
        assertTrue(searchFeatures("原生通知图标").first().feature.hasDetailPage)
    }

    @Test fun panelsMixSwitchesAndEntriesByFunctionNotControlType() {
        val features = featuresOfScopePage("systemui", ScopeFeatureGroup.STATUS_BAR)
        val panels = featurePanels(features)
        val icons = panels.entries.single { it.key.panel == FeaturePanel.ICONS }.value
        assertTrue(icons.any { it.hasDetailPage })
        assertTrue(icons.any { !it.hasDetailPage })
        assertEquals(listOf("native_notify_icon", "status_bar_icons", "status_bar_screenshot_hide"), icons.map { it.id })
        assertEquals(features.map { it.id }.sorted(), panels.values.flatten().map { it.id }.sorted())
    }

    @Test fun singleFeatureStillHasSemanticPanelAndNoEmptySection() {
        assertEquals(listOf(FeaturePanel.TRANSFER), featurePanels(featuresOfScopePage("mishare")).keys.map { it.panel })
        assertEquals(listOf(FeaturePanel.CARD), featurePanels(featuresOfScopePage("tsmclient")).keys.map { it.panel })
        assertTrue(featurePanels(emptyList()).isEmpty())
    }
}
