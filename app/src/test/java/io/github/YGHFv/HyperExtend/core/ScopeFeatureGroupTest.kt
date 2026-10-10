/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class ScopeFeatureGroupTest {
    @Test fun statusBarContainsAllRelatedFeaturesExactlyOnce() {
        val expected = STATUS_BAR_FEATURES.map { it.id }.toSet() + "native_notify_icon"
        val children = featuresOfScopePage("systemui", ScopeFeatureGroup.STATUS_BAR)
        assertEquals(expected, children.map { it.id }.toSet())
        assertEquals(expected.size, children.size)
        assertEquals(
            listOf(ScopeFeatureGroup.STATUS_BAR, ScopeFeatureGroup.LOCK_SCREEN,
                ScopeFeatureGroup.CONTROL_CENTER, ScopeFeatureGroup.SYSTEM_UI_OTHER),
            featureGroupsOfScope("systemui"),
        )
    }

    @Test fun systemUiMainPageContainsOnlySubmenusAndLegacyKeysStayInOther() {
        assertTrue(featuresOfScopePage("systemui").isEmpty())
        assertEquals(
            setOf("gesture_line", "wallpaper_monet", "rotation_suggestion"),
            featuresOfScopePage("systemui", ScopeFeatureGroup.SYSTEM_UI_OTHER)
                .filterNot { it in SYSTEM_UI_FEATURES }.map { it.id }.toSet(),
        )
    }

    @Test fun groupingDoesNotLoseFeaturesOrChangeHostCounts() {
        val all = featuresOfScope("systemui")
        val visible = featuresOfScopePage("systemui") +
            featureGroupsOfScope("systemui").flatMap { featuresOfScopePage("systemui", it) }
        assertEquals(all.map { it.id }.sorted(), visible.map { it.id }.sorted())
        val switches = all.associate { it.id to true }
        assertEquals(all.size, enabledCountInScope("systemui", switches, emptyMap()))
    }

    @Test fun otherScopesAndCrossScopeFeatureRemainUnchanged() {
        assertTrue(featureGroupsOfScope("screenshot").isEmpty())
        assertEquals(featuresOfScope("screenshot"), featuresOfScopePage("screenshot"))
        val feature = featureById("status_bar_screenshot_hide")!!
        assertEquals(listOf("systemui", "screenshot"), feature.scopes)
        assertEquals("systemui", feature.entryScope!!.id)
        assertFalse(featuresOfScopePage("screenshot").contains(feature))
    }

    @Test fun submenuTitleIsSearchable() {
        val ids = searchFeatures(ScopeFeatureGroup.STATUS_BAR.title).map { it.feature.id }.toSet()
        assertTrue(ids.containsAll(featuresOfScopePage("systemui", ScopeFeatureGroup.STATUS_BAR).map { it.id }))
    }
}
