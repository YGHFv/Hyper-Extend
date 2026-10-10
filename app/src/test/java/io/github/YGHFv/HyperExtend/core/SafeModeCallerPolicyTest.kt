/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SafeModeCallerPolicyTest {
    @Test fun hostMayReportOnlyItsOwnPackage() {
        assertTrue(SafeModeCallerPolicy.allowed("systemui", "com.android.systemui", 10001, setOf("com.android.systemui")))
        assertFalse(SafeModeCallerPolicy.allowed("systemui", "com.android.systemui", 10001, setOf("attacker")))
        assertFalse(SafeModeCallerPolicy.allowed("systemui", "com.android.systemui", 0, setOf("com.android.systemui")))
        assertFalse(SafeModeCallerPolicy.allowed("systemui", "com.android.systemui", 10001, emptySet()))
    }

    @Test fun frameworkReportRequiresSystemUid() {
        assertTrue(SafeModeCallerPolicy.allowed("system_server", "system", 1000, emptySet()))
        assertFalse(SafeModeCallerPolicy.allowed("system_server", "system", 10001, setOf("android")))
        assertFalse(SafeModeCallerPolicy.allowed("system_server", "system", 0, emptySet()))
    }
}
