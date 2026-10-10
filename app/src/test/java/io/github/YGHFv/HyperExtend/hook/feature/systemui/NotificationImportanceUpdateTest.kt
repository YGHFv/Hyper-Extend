/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class NotificationImportanceUpdateTest {
    private data class Channel(val id: String = "channel", val level: Int = 3, val locks: Int = 16, val editable: Boolean = true)
    private class Backend(var server: Channel? = Channel()) : ImportanceChannelAccess<Channel> {
        var ignoreWrite = false
        var throwWrite = false
        var loseReadBack = false
        var writes = 0
        var reads = 0
        override fun read(): Channel? { reads++; return if (loseReadBack && reads > 1) null else server }
        override fun matches(channel: Channel) = channel.id == "channel"
        override fun editable(channel: Channel) = channel.editable && channel.level in 1..4
        override fun importance(channel: Channel) = channel.level
        override fun changedCopy(channel: Channel, level: Int) = channel.copy(level = level, locks = channel.locks or 4)
        override fun write(channel: Channel) { writes++; if (throwWrite) error("Binder failure"); if (!ignoreWrite) server = channel }
    }
    @Test fun changedChannelIsConfirmedBySecondServerRead() {
        val backend = Backend(); val old = backend.server!!
        val result = updateImportance(1, backend)
        assertTrue(result.confirmed); assertEquals(1, result.actual!!.level)
        assertEquals(2, backend.reads); assertEquals(1, backend.writes)
        assertEquals(3, old.level); assertEquals(16, old.locks); assertEquals(20, result.actual.locks)
    }
    @Test fun swallowedBackendFailureDoesNotLookLikeSuccess() {
        val backend = Backend().apply { ignoreWrite = true }
        val result = updateImportance(1, backend)
        assertFalse(result.confirmed); assertEquals(3, result.actual!!.level)
    }
    @Test fun unknownReadBackDoesNotClaimSuccessOrRollbackServer() {
        val backend = Backend().apply { loseReadBack = true }
        val result = updateImportance(1, backend)
        assertFalse(result.confirmed); assertNull(result.actual); assertEquals(1, backend.server!!.level)
        assertEquals(1, backend.writes)
    }
    @Test fun unchangedValueDoesNotWriteOrLockChannel() {
        val backend = Backend(); assertTrue(updateImportance(3, backend).confirmed)
        assertEquals(0, backend.writes); assertEquals(16, backend.server!!.locks)
    }
    @Test fun noChannelAndWrongChannelNeverWrite() {
        for (channel in listOf(null, Channel(id = "other"))) {
            val backend = Backend(channel); assertFalse(updateImportance(1, backend).confirmed); assertEquals(0, backend.writes)
        }
    }
    @Test fun disabledAndRestrictedChannelsNeverWrite() {
        for (channel in listOf(Channel(editable = false), Channel(level = 0), Channel(level = -1000))) {
            val backend = Backend(channel); assertFalse(updateImportance(1, backend).confirmed); assertEquals(0, backend.writes)
        }
    }
    @Test fun invalidLevelDoesNotEvenReadServer() {
        for (level in listOf(-1000, 0, 5)) {
            val backend = Backend(); assertFalse(updateImportance(level, backend).confirmed); assertEquals(0, backend.reads)
        }
    }
    @Test fun writeExceptionLeavesOriginalChannelUntouched() {
        val backend = Backend().apply { throwWrite = true }; val original = backend.server
        assertTrue(runCatching { updateImportance(1, backend) }.isFailure)
        assertSame(original, backend.server)
    }
    class HostDropdown {
        private fun findSpinnerIndexOfValue(value: String) = value.toInt() - 1
        fun findIndexOfValue(value: String) = findSpinnerIndexOfValue(value)
    }
    @Test fun publicLookupCannotResolvePrivateSpinnerButCanResolveForwarder() {
        assertTrue(runCatching { HostDropdown::class.java.getMethod("findSpinnerIndexOfValue", String::class.java) }.isFailure)
        val method = HostDropdown::class.java.getMethod(NotificationImportanceSettingsTargets.index.name, String::class.java)
        assertEquals(2, method.invoke(HostDropdown(), "3"))
    }
    @Test fun everyHookAndQueryHasExactSettingsDexSignature() {
        fun descriptor(name: String) = when (name) { "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; else -> "L${name.replace('.', '/')};" }
        val fixture = javaClass.getResourceAsStream("/notification-importance-settings-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (spec in NotificationImportanceSettingsTargets.hooks + NotificationImportanceSettingsTargets.queries + NotificationImportanceSettingsTargets.callers) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" + spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in fixture)
        }
        assertEquals("findIndexOfValue", NotificationImportanceSettingsTargets.index.name)
        assertTrue(NotificationImportanceSettingsTargets.callers.contains(NotificationImportanceSettingsTargets.resume))
    }
    @Test fun panelAndLegacyPagesEachOwnTheirResumeAndDependentUpdate() {
        val targets = NotificationImportanceSettingsTargets
        assertEquals(setOf("com.android.settings.notification.ChannelNotificationSettings",
            "com.android.settings.notification.app.ChannelNotificationSettings"), targets.pages.toSet())
        assertEquals(targets.pages.toSet(), targets.resumes.map { it.owner }.toSet())
        assertEquals(targets.pages.toSet(), targets.dependentUpdates.map { it.owner }.toSet())
        assertEquals(3, targets.hooks.size)
        assertEquals(1, targets.hooks.count { it == targets.visible })
        for (page in targets.pages) {
            assertTrue(targets.callers.contains(targets.resume.copy(owner = page)))
            assertTrue(targets.callers.contains(targets.remove.copy(owner = page)))
            assertTrue(targets.queries.contains(targets.dependents.copy(owner = page)))
        }
    }
}
