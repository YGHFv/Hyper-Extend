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

    @Test fun mixedPagesKeepEachTypeInCatalogOrder() {
        val features = featuresOfScopePage("systemui", ScopeFeatureGroup.STATUS_BAR)
        val (details, switches) = features.partition { it.hasDetailPage }
        assertEquals(6, details.size)
        assertEquals(listOf("status_bar_double_tap", "status_bar_screenshot_hide"), switches.map { it.id })
        assertEquals(features.map { it.id }, (details + switches).map { it.id })
        assertEquals(features.size, (details + switches).distinctBy { it.id }.size)
    }

    @Test fun singleTypePagesDoNotNeedAnEmptySection() {
        val (details, switches) = featuresOfScopePage("mishare").partition { it.hasDetailPage }
        assertTrue(details.isEmpty())
        assertEquals(listOf("mishare_receive_guard"), switches.map { it.id })
        val (cardDetails, cardSwitches) = featuresOfScopePage("tsmclient").partition { it.hasDetailPage }
        assertEquals(1, cardDetails.size)
        assertTrue(cardSwitches.isEmpty())
    }
}
