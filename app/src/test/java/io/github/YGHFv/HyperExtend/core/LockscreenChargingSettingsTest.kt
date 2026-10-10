/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings
import org.junit.Assert.*
import org.junit.Test

class LockscreenChargingSettingsTest {
    @Test fun entryAndSuboptionsAreOptInAndPersisted() {
        val feature = featureById(LockscreenChargingSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.LOCK_SCREEN, feature.entryGroup)
        assertEquals(3, feature.options.size)
        assertTrue(feature.options.all { !it.defaultEnabled })
        assertTrue(feature.config.all { it.key in HyperSettings.STRING_KEYS })
    }
    @Test fun intervalPreservesHalfSecondsInsteadOfIntegerDivision() {
        assertEquals(1500L, LockscreenChargingSettings.interval("3", true))
        assertEquals(2500L, LockscreenChargingSettings.interval("5", true))
        assertEquals(3000L, LockscreenChargingSettings.interval("2", false))
    }
    @Test fun invalidValuesDefaultOrClampToSupportedRange() {
        for (raw in listOf(null, "", "bad", "99999999999999"))
            assertEquals(3000L, LockscreenChargingSettings.interval(raw, true))
        assertEquals(1000L, LockscreenChargingSettings.interval("-5", true))
        assertEquals(5000L, LockscreenChargingSettings.interval("999", true))
    }
}
