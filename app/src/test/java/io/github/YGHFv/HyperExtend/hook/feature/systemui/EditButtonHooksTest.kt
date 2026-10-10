/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class EditButtonHooksTest {
    @Test fun unsupportedOrDisabledFeatureKeepsNativeAvailability() {
        assertTrue(EditButtonHooks.visible(true, false, 202602260, 183022200))
        assertTrue(EditButtonHooks.visible(true, true, null, 183022200))
        assertTrue(EditButtonHooks.visible(true, true, 202602260, 183022201))
        assertFalse(EditButtonHooks.visible(true, true, 202602260, 183022200))
    }
    @Test fun neverEnablesNativeUnavailableEntry() {
        assertFalse(EditButtonHooks.visible(false, false, null, null))
        assertFalse(EditButtonHooks.visible(false, true, 202602260, 183022200))
    }
}
