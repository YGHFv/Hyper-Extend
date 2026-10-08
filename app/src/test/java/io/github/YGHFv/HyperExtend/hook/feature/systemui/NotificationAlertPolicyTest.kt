/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationAlertPolicyTest {
    @Test fun disabledFeaturesNeverMute() {
        for (filter in 0..5) assertFalse(NotificationAlertPolicy.mute(false, true, false, filter))
    }

    @Test fun zenFixIncludesAllThreeExplicitDndModesButNotUnknown() {
        for (filter in 2..4) assertTrue(NotificationAlertPolicy.mute(false, false, true, filter))
        for (filter in listOf(null, -1, 0, 1, 5)) assertFalse(NotificationAlertPolicy.mute(false, false, true, filter))
    }

    @Test fun interactiveAndZenSwitchesComposeWithoutDuplicateHooks() {
        assertTrue(NotificationAlertPolicy.mute(true, true, true, 1))
        assertTrue(NotificationAlertPolicy.mute(true, false, true, 2))
        assertFalse(NotificationAlertPolicy.mute(true, false, false, 2))
        assertFalse(NotificationAlertPolicy.mute(true, null, true, null))
    }
}
