/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class EditButtonHooksTest {
    @Test fun enabledFeatureHidesOnlyNativeAvailableEntry() {
        assertTrue(EditButtonHooks.visible(true, false))
        assertFalse(EditButtonHooks.visible(true, true))
    }
    @Test fun neverEnablesNativeUnavailableEntry() {
        assertFalse(EditButtonHooks.visible(false, false))
        assertFalse(EditButtonHooks.visible(false, true))
    }
}
