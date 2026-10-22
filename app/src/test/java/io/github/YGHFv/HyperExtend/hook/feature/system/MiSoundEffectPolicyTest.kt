/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_DOLBY
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_MISOUND
import io.github.YGHFv.HyperExtend.core.MISOUND_EFFECT_NONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiSoundEffectPolicyTest {
    @Test fun firstConnectionUsesRememberedDeviceState() {
        val remembered = MiSoundEffectState(
            main = MISOUND_EFFECT_DOLBY,
            spatial = true,
            surround = false,
        )

        assertEquals(
            remembered,
            MiSoundEffectPolicy.connectionTarget(remembered, MISOUND_EFFECT_NONE),
        )
    }

    @Test fun firstConnectionWithoutMemoryUsesConfiguredDefaultMainEffect() {
        assertEquals(
            MiSoundEffectState(MISOUND_EFFECT_MISOUND),
            MiSoundEffectPolicy.connectionTarget(null, MISOUND_EFFECT_MISOUND),
        )
    }

    @Test fun missingOptionalFieldsDoNotOverrideCurrentHostValues() {
        val legacy = MiSoundEffectState(MISOUND_EFFECT_DOLBY)
        val effective = MiSoundEffectPolicy.forBackend(legacy, supportsThreeState = true)

        assertNull(effective.spatial)
        assertNull(effective.surround)
    }

    @Test fun nonFrameworkBackendOnlyAppliesMainEffect() {
        val effective = MiSoundEffectPolicy.forBackend(
            MiSoundEffectState(MISOUND_EFFECT_DOLBY, spatial = true, surround = true),
            supportsThreeState = false,
        )

        assertEquals(MISOUND_EFFECT_DOLBY, effective.main)
        assertNull(effective.spatial)
        assertNull(effective.surround)
    }

    @Test fun legacyDeviceMainEffectStringRemainsReadable() {
        assertEquals(
            MiSoundEffectState(MISOUND_EFFECT_DOLBY),
            MiSoundEffectPolicy.legacyEffect(MISOUND_EFFECT_DOLBY),
        )
        assertNull(MiSoundEffectPolicy.legacyEffect("unknown"))
    }
}
