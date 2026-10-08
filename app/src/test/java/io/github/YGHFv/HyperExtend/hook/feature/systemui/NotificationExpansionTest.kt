/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class NotificationExpansionTest {
    @Test fun onlyExpandedPinnedAlertsCanTimeOut() {
        assertTrue(ExpandedTimeoutPolicy.eligible(true, true, false, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(false, true, false, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, false, false, false, false))
    }
    @Test fun replyMenusAndFullScreenAlertsAreProtected() {
        assertFalse(ExpandedTimeoutPolicy.eligible(true, true, true, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, true, false, true, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, true, false, false, true))
    }
    @Test fun unknownStateNeverRemovesAnAlert() {
        assertFalse(ExpandedTimeoutPolicy.eligible(null, true, false, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, null, false, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, true, null, false, false))
        assertFalse(ExpandedTimeoutPolicy.eligible(true, true, false, null, false))
    }
    @Test fun settingsAreRegisteredWithSafeDefaults() {
        assertTrue(CONFIG_KEYS.contains(NotificationExpansionSettings.PACKAGES))
        assertTrue(CONFIG_KEYS.contains(NotificationExpansionSettings.timeout.key))
        assertEquals(45, NotificationExpansionSettings.timeout.default)
        assertFalse(featureById(NotificationExpansionSettings.EXPAND)!!.defaultEnabled)
        assertFalse(featureById(NotificationExpansionSettings.COLLAPSE)!!.defaultEnabled)
        assertTrue(featureById("control_center_hide_carrier")!!.requirement!!.contains("隐私"))
    }
    @Test fun os4PresenterAndCarrierSignaturesAreNotLegacyOverloads() {
        assertEquals(3, NotificationExpansionHooks.presenterTarget.parameters.size)
        assertEquals("com.android.systemui.statusbar.notification.collection.EntryAdapter",
            NotificationExpansionHooks.presenterTarget.parameters[1])
        assertEquals("boolean", NotificationExpansionHooks.presenterTarget.parameters[2])
        assertEquals(2, NotificationExpansionHooks.carrierTarget.parameters.size)
        assertTrue(NotificationExpansionHooks.carrierTarget.parameters.all { it.contains("PromptInfo") })
    }
}
