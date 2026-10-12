/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class SecurityFooterPolicyTest {
    @Test fun suppressionRequiresKnownMainDisplayAndExactHost() {
        assertTrue(SecurityFooterHooks.suppress(true, true, true, 0, 202602260L))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, null, 202602260L))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 1, 202602260L))
        assertFalse(SecurityFooterHooks.suppress(true, true, false, 0, 202602260L))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 0, 202602261L))
        assertFalse(SecurityFooterHooks.suppress(true, false, true, 0, 202602260L))
        assertFalse(SecurityFooterHooks.suppress(false, true, true, 0, 202602260L))
    }
    @Test fun pluginRequiresBothVersions() {
        assertTrue(SecurityFooterHooks.suppress(true, true, true, 0, 202602260L, 183022200L, true))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 0, 202602260L, null, true))
        assertFalse(SecurityFooterHooks.suppress(true, true, true, 0, 202602260L, 183022201L, true))
    }
}
