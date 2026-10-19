/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class SecurityFooterPolicyTest {
    @Test fun suppressionRequiresKnownMainDisplayAndExactHost() {
        assertTrue(SecurityFooterHooks.suppress(true, true, true, 0))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, null))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 1))
        assertFalse(SecurityFooterHooks.suppress(true, true, false, 0))
        assertFalse(SecurityFooterHooks.suppress(true, false, true, 0))
        assertFalse(SecurityFooterHooks.suppress(false, true, true, 0))
    }
    @Test fun pluginPathKeepsNativeBehaviorWithoutVersionGate() {
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 0, true))
    }
}
