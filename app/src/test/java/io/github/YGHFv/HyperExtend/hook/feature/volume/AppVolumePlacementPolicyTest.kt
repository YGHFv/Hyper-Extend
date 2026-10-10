/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import io.github.YGHFv.HyperExtend.core.APP_VOLUME_FEATURE
import io.github.YGHFv.HyperExtend.core.AppVolumeButtonPosition
import io.github.YGHFv.HyperExtend.core.AppVolumeButtonPosition.*
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.CONFIG_KEYS
import io.github.YGHFv.HyperExtend.core.HyperChoice
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import org.junit.Assert.*
import org.junit.Test

class AppVolumePlacementPolicyTest {
    @Test fun sixChoicesAreOrderedAndPersistedThroughExistingConfigCatalog() {
        assertEquals("统一多应用音量调节样式", APP_VOLUME_FEATURE.title)
        assertEquals("volume_app_entry", APP_VOLUME_FEATURE.id)
        val row = APP_VOLUME_FEATURE.config.single() as HyperChoice
        assertEquals("多应用音量调节按钮位置", row.title)
        assertEquals(listOf("新增·音量条上方", "新增·静音按钮上方", "新增·勿扰按钮上方",
            "新增·勿扰按钮下方", "替换·静音按钮", "替换·勿扰按钮"), row.entries.map { it.label })
        assertEquals(6, row.entries.map { it.variant }.toSet().size)
        assertEquals(AppVolumeSettings.POSITION, row.key)
        assertTrue(row.key in CONFIG_KEYS)
        assertTrue(APP_VOLUME_FEATURE.hasDetailPage)
        assertEquals(ABOVE_VOLUME.variant, row.default)
    }

    @Test fun missingOrUnknownValuePreservesPreviousTopEntry() {
        listOf(null, "", "invalid", "5").forEach { assertEquals(ABOVE_VOLUME, AppVolumeButtonPosition.fromVariant(it)) }
        AppVolumeButtonPosition.entries.forEach { assertEquals(it, AppVolumeButtonPosition.fromVariant(it.variant)) }
    }

    @Test fun nativeSpacerAndButtonOrderingArePreserved() {
        val expected = mapOf(ABOVE_VOLUME to 0, ABOVE_SILENT to 0, ABOVE_DND to 2,
            BELOW_DND to 3, REPLACE_SILENT to 0, REPLACE_DND to 2)
        expected.forEach { (position, index) -> assertEquals(index, AppVolumePlacementPolicy.insertionIndex(position, 0, 2)) }
        assertEquals(-1, AppVolumePlacementPolicy.insertionIndex(BELOW_DND, 0, -1))
        assertEquals(-1, AppVolumePlacementPolicy.insertionIndex(REPLACE_SILENT, -1, 2))
    }

    @Test fun everyPositionUsesTheSamePlaybackAndLockGate() {
        AppVolumeButtonPosition.entries.forEach { _ ->
            assertTrue(AppVolumePolicy.visible(true, false, false, true, false))
            assertFalse(AppVolumePolicy.visible(true, false, false, false, false))
            assertFalse(AppVolumePolicy.visible(true, false, true, true, false))
            assertFalse(AppVolumePolicy.visible(true, true, false, true, false))
        }
    }

    @Test fun portraitFooterRequiresBottomRoomWithoutMovingSliders() {
        assertTrue(AppVolumePlacementPolicy.footerFits(100, 400, 60, 560, false))
        assertFalse(AppVolumePlacementPolicy.footerFits(100, 400, 60, 559, false))
        assertTrue(AppVolumePlacementPolicy.footerFits(0, 400, 60, 460, false))
    }

    @Test fun landscapeFooterCentersTheWholeStackAndChecksBothEdges() {
        assertTrue(AppVolumePlacementPolicy.footerFits(50, 400, 60, 480, true))
        assertFalse(AppVolumePlacementPolicy.footerFits(29, 400, 60, 1000, true))
        assertFalse(AppVolumePlacementPolicy.footerFits(50, 400, 60, 479, true))
        assertTrue(AppVolumePlacementPolicy.footerFits(31, 400, 61, 461, true))
    }

    @Test fun replacementsNeedNoAdditionalSpaceAndRejectInvalidGeometry() {
        assertTrue(AppVolumePlacementPolicy.footerFits(0, 400, 0, 400, false))
        assertFalse(AppVolumePlacementPolicy.footerFits(0, 0, 0, 400, false))
        assertFalse(AppVolumePlacementPolicy.footerFits(-1, 400, 0, 400, false))
        assertFalse(AppVolumePlacementPolicy.footerFits(1, 400, -1, 400, false))
    }

    @Test fun repeatedReplacementRestoresOriginalVisibilityOnlyOnce() {
        val state = AppVolumeReplacementState()
        var visibility = state.hide(0, 8)
        repeat(10) { visibility = state.hide(visibility, 8) }
        assertEquals(0, state.nativeVisibility(visibility, 8))
        assertEquals(0, state.restore(visibility, 8))
        assertEquals(4, state.restore(4, 8))
    }

    @Test fun nativeHiddenOrNewerVisibilityIsNeverForcedVisible() {
        val state = AppVolumeReplacementState()
        state.hide(8, 8)
        assertEquals(8, state.nativeVisibility(8, 8))
        assertEquals(8, state.restore(8, 8))
        state.hide(0, 8)
        assertEquals(4, state.restore(4, 8))
        state.hide(0, 8)
        state.hide(4, 8)
        assertEquals(4, state.restore(8, 8))
    }
}
