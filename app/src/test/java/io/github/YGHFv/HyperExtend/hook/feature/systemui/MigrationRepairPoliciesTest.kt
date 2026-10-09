/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class MigrationRepairPoliciesTest {
    @Test fun conversationNeverCrossesPackageChannelOrProfile() {
        fun resolve(pkg: String? = "app", uid: Int? = 1010001, channel: String? = "messages", shortcut: String? = "person") =
            NotificationChannelPolicy.conversation("app", 1010001, "messages", pkg, uid, channel, shortcut)
        assertEquals("person", resolve())
        assertNull(resolve(pkg = "other")); assertNull(resolve(uid = 10001))
        assertNull(resolve(channel = "other")); assertNull(resolve(shortcut = ""))
        assertNull(resolve(shortcut = null))
    }
    @Test fun customButtonsOverrideMainSizeOnlyWhenConfigured() {
        assertNull(MediaButtonSizePolicy.size(140, 140, true))
        assertNull(MediaButtonSizePolicy.size(140, 140, false))
        assertEquals(90, MediaButtonSizePolicy.size(90, 140, false))
        assertEquals(120, MediaButtonSizePolicy.size(90, 120, false))
        assertEquals(90, MediaButtonSizePolicy.size(90, 120, true))
    }
    @Test fun iconScalingPreservesAspectAndNeverOutgrowsTouchTarget() {
        assertEquals(0.5f, MediaButtonSizePolicy.scale(50, 120, 120, 100, 100)!!, 0f)
        assertEquals(0.3f, MediaButtonSizePolicy.scale(200, 100, 60, 200, 100)!!, 0f)
        assertNull(MediaButtonSizePolicy.scale(100, 0, 100, 100, 100))
        assertNull(MediaButtonSizePolicy.scale(100, 100, 100, -1, -1))
    }
}
