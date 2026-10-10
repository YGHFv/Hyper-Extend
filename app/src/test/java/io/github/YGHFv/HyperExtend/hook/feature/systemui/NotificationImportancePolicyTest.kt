/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationImportancePolicyTest {
    @Test fun minimumImportanceDoesNotCountButUnknownIsPreserved() {
        assertFalse(NotificationImportancePolicy.counted(0))
        assertFalse(NotificationImportancePolicy.counted(1))
        for (level in listOf(null, -1000, 2, 3, 4, 5)) assertTrue(NotificationImportancePolicy.counted(level))
    }

    @Test fun selectionCannotBlockChannelsOrRequestUnsupportedLevels() {
        for (level in 1..4) assertEquals(level, NotificationImportancePolicy.selection(level.toString()))
        for (value in listOf(null, "", "0", "5", "-1", "NaN", "1.0", 1)) assertNull(NotificationImportancePolicy.selection(value))
    }

    @Test fun preferenceVisibilityDoesNotEnableUnrelatedOrPolicyControls() {
        assertTrue(NotificationImportancePolicy.expose("importance"))
        for (key in listOf(null, "badge", "allow_keyguard", "block", "bypass_dnd", "admin", "importance_other")) assertFalse(NotificationImportancePolicy.expose(key))
    }

    @Test fun flattenedChildrenAreIndependentlyFilteredAndInputRemainsUnchanged() {
        val original = listOf(1, 4, 1, 2, -1000)
        assertEquals(listOf(4, 2, -1000), original.filter(NotificationImportancePolicy::counted))
        assertEquals(listOf(1, 4, 1, 2, -1000), original)
    }
}
