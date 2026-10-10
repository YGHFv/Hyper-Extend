/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class LockscreenWallpaperSettingsTest {
    @Test fun optInFeatureHasTwoIndependentDurationsAndNoExtraScope() {
        val feature = featureById(LockscreenWallpaperSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui"), feature.scopes)
        assertEquals(ScopeFeatureGroup.LOCK_SCREEN, feature.group)
        assertEquals(FeaturePanel.LOCK_APPEARANCE, feature.panel)
        assertEquals(listOf(LockscreenWallpaperSettings.WAKE, LockscreenWallpaperSettings.SLEEP), feature.config.map { it.key })
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        assertTrue(feature.requirement!!.contains("不接管壁纸表面和唤醒锁"))
    }

    @Test fun millisecondsFollowXmlDefaultsNotTheOldMisleadingRateLabel() {
        assertEquals(300L, LockscreenWallpaperSettings.duration(true, ""))
        assertEquals(200L, LockscreenWallpaperSettings.duration(false, ""))
        assertEquals("300 ms", LockscreenWallpaperSettings.wake.display(300))
        assertEquals("200 ms", LockscreenWallpaperSettings.sleep.display(200))
        assertEquals(321L, LockscreenWallpaperSettings.duration(true, "321"))
        assertEquals(456L, LockscreenWallpaperSettings.duration(false, "456"))
    }

    @Test fun malformedBackupsAndOutOfRangeValuesCannotExtendWakeLockUnboundedly() {
        for (raw in listOf("NaN", "300.5", "999999999999999")) {
            assertEquals(300L, LockscreenWallpaperSettings.duration(true, raw))
            assertEquals(200L, LockscreenWallpaperSettings.duration(false, raw))
        }
        for (show in listOf(false, true)) {
            assertEquals(100L, LockscreenWallpaperSettings.duration(show, "-1"))
            assertEquals(100L, LockscreenWallpaperSettings.duration(show, "0"))
            assertEquals(1600L, LockscreenWallpaperSettings.duration(show, "2147483647"))
        }
    }
}
