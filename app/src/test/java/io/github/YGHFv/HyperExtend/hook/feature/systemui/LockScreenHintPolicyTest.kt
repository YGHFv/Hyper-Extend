/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class LockScreenHintPolicyTest {
    @Test fun removesOnlyVerifiedOperationHints() {
        LockScreenHintPolicy.types.forEach { (id, name) ->
            assertTrue(LockScreenHintPolicy.hide(id, name))
            assertFalse(LockScreenHintPolicy.hide(id, "unknown[$id]"))
            assertFalse(LockScreenHintPolicy.hide(id, null))
        }
    }

    @Test fun preservesBatteryPolicyAndAuthenticationWarnings() {
        for (id in -1..12) assertFalse(LockScreenHintPolicy.hide(id, "is_dismissible"))
        assertFalse(LockScreenHintPolicy.hide(14, "adaptive_auth"))
        assertFalse(LockScreenHintPolicy.hide(15, "watch_disconnected"))
        assertFalse(LockScreenHintPolicy.hide(16, "secure_lock_device"))
    }
}
