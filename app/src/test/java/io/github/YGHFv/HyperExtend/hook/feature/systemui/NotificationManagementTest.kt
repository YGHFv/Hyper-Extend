/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.NotificationManagementSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class NotificationManagementTest {
    @Test fun featureRequiresExplicitOptInAndBothNativeHosts() {
        val feature = featureById(NotificationManagementSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(FeaturePanel.NOTIFICATIONS, feature.panel)
        assertEquals(listOf("systemui", "settings", "system_server"), feature.scopes)
    }

    @Test fun onlyOwnerUserSystemRequestedUserEditsCanAdaptChannelQuery() {
        val p = NotificationManagementPolicy
        assertTrue(p.userChannelWrite(true, true, 1000, true, 10001))
        assertFalse(p.userChannelWrite(false, true, 1000, true, 10001))
        assertFalse(p.userChannelWrite(true, false, 1000, true, 10001))
        assertFalse(p.userChannelWrite(true, true, 10001, true, 10001))
        assertFalse(p.userChannelWrite(true, true, 1000, false, 10001))
        for (uid in listOf(-1, 0, 100000, 101000, Int.MAX_VALUE))
            assertFalse(p.userChannelWrite(true, true, 1000, true, uid))
        assertTrue(p.ownerUid(1000))
        assertTrue(p.ownerUid(99999))
    }

    @Test fun queryMustComeDirectlyFromNativeChannelUpdate() {
        val t = NotificationManagementTargets
        val update = t.update.owner to t.update.name
        val query = t.CHANNEL to "isBlockable"
        assertTrue(NotificationManagementPolicy.directChannelUpdate(listOf(query, update)))
        for (other in listOf(t.CHANNEL to "writeXml", t.CHANNEL to "equals",
            t.PREFERENCES to "createNotificationChannel", t.updateCaller.owner to "enqueueNotificationInternal")) {
            assertFalse(NotificationManagementPolicy.directChannelUpdate(listOf(query, other, update)))
        }
        assertFalse(NotificationManagementPolicy.directChannelUpdate(emptyList()))
    }

    @Test fun scopeIsNestedThreadLocalAndRestoredAfterHostFailure() {
        val scope = NativeCallScope<String>()
        assertNull(scope.value)
        scope.within("outer") {
            runCatching { scope.within("inner") { assertEquals("inner", scope.value); error("native failure") } }
            assertEquals("outer", scope.value)
            scope.within(null) { assertNull(scope.value) }
            assertEquals("outer", scope.value)
            var other: String? = "unexpected"
            Thread { other = scope.value }.apply { start(); join() }
            assertNull(other)
        }
        assertNull(scope.value)
    }

    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
        else -> if (type.startsWith("[")) type.replace('.', '/') else "L${type.replace('.', '/')};"
    }

    @Test fun exactMethodsFieldsAndBothFilterOverloadsHaveCurrentEvidence() {
        val fixture = javaClass.getResourceAsStream("/notification-management-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = NotificationManagementTargets
        for (method in t.settingsMethods + t.serverMethods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixture)
        }
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixture)
        assertEquals(16, fixture.size)
        assertTrue(listOf(t.toggle, t.forced, t.enable, t.enableForContext).all { it.isStatic })
        assertFalse(t.row.isStatic)
        assertTrue((t.settingsMethods + t.serverMethods).none { it.name == "setBlockable" })
    }
}
