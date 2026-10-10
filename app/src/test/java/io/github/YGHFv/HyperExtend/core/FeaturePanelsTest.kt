/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class FeaturePanelsTest {
    @Test fun everyCurrentFeatureHasExactlyOneDeclaredPanel() {
        val declared = FeaturePanel.entries.flatMap { it.featureIds.toList() }
        assertEquals(declared.size, declared.distinct().size)
        assertEquals(FEATURES.map { it.id }.toSet(), declared.toSet())
        assertTrue(FEATURES.none { it.panel == FeaturePanel.OTHER })
    }
    @Test fun changingRowControlsNeverChangesItsPanel() {
        val original = featureById("media_card_always_dark")!!
        val withDetail = original.copy(config = listOf(HyperText("test", "Test")))
        assertFalse(original.hasDetailPage); assertTrue(withDetail.hasDetailPage)
        assertEquals(original.panelKey, withDetail.panelKey)
    }
    @Test fun notificationsAndMediaKeepRelatedSwitchesAndEntriesTogether() {
        val panels = featurePanels(featuresOfScopePage("systemui", ScopeFeatureGroup.CONTROL_CENTER))
        for (panel in listOf(FeaturePanel.NOTIFICATIONS, FeaturePanel.MEDIA, FeaturePanel.VOLUME)) {
            val rows = panels.entries.single { it.key.panel == panel }.value
            assertTrue(rows.any { it.hasDetailPage }); assertTrue(rows.any { !it.hasDetailPage })
        }
        assertFalse(panels.keys.any { it.panel == FeaturePanel.LOCK_SHORTCUTS })
    }
    @Test fun groupingPreservesEveryFeatureExactlyOnceAndOrderInsidePanel() {
        for (scope in entryScopes()) for (group in listOf(null) + featureGroupsOfScope(scope.id)) {
            val source = featuresOfScopePage(scope.id, group)
            val grouped = featurePanels(source)
            assertEquals(source.map { it.id }.sorted(), grouped.values.flatten().map { it.id }.sorted())
            for ((key, rows) in grouped) assertEquals(source.filter { it.panelKey == key }, rows)
            assertEquals(source.map { it.panelKey }.distinct(), grouped.keys.toList())
        }
    }
    @Test fun searchUsesSameSemanticKeyWithoutMergingHosts() {
        val hits = searchFeatures("剪贴板")
        val grouped = groupFeaturePanels(hits) { it.feature }
        assertTrue(grouped.size >= 2)
        for ((key, values) in grouped) assertTrue(values.all { it.feature.panelKey == key })
        assertEquals(hits.size, grouped.values.sumOf { it.size })
    }
    @Test fun panelNamesAreSearchableAndUnknownFeaturesStillRender() {
        val ids = searchFeatures("底部快捷入口").map { it.feature.id }.toSet()
        assertTrue(ids.containsAll(FeaturePanel.LOCK_SHORTCUTS.featureIds.toList()))
        val unknown = featureById("rotation_lock_fix")!!.copy(id = "future_feature")
        assertEquals(FeaturePanel.OTHER, featurePanels(listOf(unknown)).keys.single().panel)
    }
    @Test fun detailSwitchesAndValuesWithSameGroupShareOnePanel() {
        val feature = featureById("status_bar_battery_style")!!
        val panels = featureDetailPanels(feature)
        val extended = panels.single { it.title == "扩展" }
        assertTrue(extended.options.isNotEmpty()); assertTrue(extended.config.isNotEmpty())
        assertEquals(feature.options, panels.flatMap { it.options })
        assertEquals(feature.config, panels.flatMap { it.config })
    }
    @Test fun ungroupedDetailControlsShareGeneralPanel() {
        val feature = featureById("rotation_lock_fix")!!.copy(
            options = listOf(HyperOption("x", "X")), config = listOf(HyperText("y", "Y")))
        val panel = featureDetailPanels(feature).single()
        assertEquals("常规", panel.title); assertEquals(1, panel.options.size); assertEquals(1, panel.config.size)
        assertTrue(featureDetailPanels(featureById("rotation_lock_fix")!!).isEmpty())
    }
    @Test fun detailGroupingNeverDropsOrDuplicatesAnyCurrentControl() {
        for (feature in FEATURES) {
            val panels = featureDetailPanels(feature)
            assertEquals(feature.options.map { it.id }.sorted(), panels.flatMap { it.options }.map { it.id }.sorted())
            assertEquals(feature.config.map { it.key }.sorted(), panels.flatMap { it.config }.map { it.key }.sorted())
            assertEquals(panels.size, panels.map { it.title }.distinct().size)
        }
    }
}
