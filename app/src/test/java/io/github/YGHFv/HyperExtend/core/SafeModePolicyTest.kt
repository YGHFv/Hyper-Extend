/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeModePolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val event = SafeModeIncident("incident-1", 10, 1_800_000_000_000, "hook failure")

    @Test fun incidentRoundTripsAndNormalizesReason() {
        assertEquals(event, SafeModeIncident.decode(event.encode()))
        assertEquals("first second", SafeModeIncident.decode(event.copy(reason = "first\nsecond").encode())!!.reason)
        assertEquals(240, SafeModeIncident.decode(event.copy(reason = "x".repeat(500)).encode())!!.reason.length)
    }

    @Test fun invalidReportsAreRejected() {
        listOf("", "bad", "id\n0\n0", "!\n0\n0\nreason", "id\n-1\n0\nreason",
            "id\n0\n-1\nreason", "id\n0\n0\n", "id\n0\n0\n${"x".repeat(241)}").forEach {
            assertNull(it, SafeModeIncident.decode(it))
        }
    }

    @Test fun recoveryTokensIncreaseEvenAfterClockMovesBackwards() {
        assertEquals(11, SafeModeKeys.nextReset(10, 1))
        assertEquals(50, SafeModeKeys.nextReset(10, 50))
        assertEquals(1, SafeModeKeys.nextReset(0, -1))
        assertTrue(runCatching { SafeModeKeys.nextReset(Long.MAX_VALUE, 1) }.isFailure)
    }

    @Test fun queuedOldReportsCannotUndoRecoveryAndFutureTokensAreRejected() {
        assertTrue(SafeModeReportPolicy.accepts(10, event))
        assertFalse(SafeModeReportPolicy.accepts(11, event))
        assertFalse(SafeModeReportPolicy.accepts(9, event))
    }

    @Test fun repeatedReportsDoNotCreateRepeatedDialogsUntilNextRecovery() {
        assertTrue(SafeModeReportPolicy.duplicate(event, event.copy(id = "incident-2")))
        assertFalse(SafeModeReportPolicy.duplicate(event, event.copy(resetToken = 11)))
        assertFalse(SafeModeReportPolicy.duplicate(null, event))
    }

    @Test fun missingStoreIsNotAnIncidentAndDurableStateRoundTrips() {
        val file = File(temp.root, "files/marker")
        val store = SafeModeStore(file)
        assertNull(store.read().getOrThrow())
        assertTrue(store.write(event))
        assertEquals(event, store.read().getOrThrow())
        assertFalse(File(file.parentFile, "marker.tmp").exists())
        assertTrue(store.clear())
        assertNull(store.read().getOrThrow())
    }

    @Test fun corruptOversizedAndDirectoryMarkersFailClosed() {
        val file = temp.newFile()
        val store = SafeModeStore(file)
        file.writeText("truncated")
        assertTrue(store.read().isFailure)
        file.writeText("x".repeat(2049))
        assertTrue(store.read().isFailure)
        assertTrue(SafeModeStore(temp.newFolder()).read().isFailure)
    }

    @Test fun failedWriteKeepsLastConfirmedIncident() {
        val file = File(temp.root, "marker")
        val store = SafeModeStore(file)
        assertTrue(store.write(event))
        assertTrue(File(temp.root, "marker.tmp").mkdir())
        assertFalse(store.write(event.copy(id = "incident-2")))
        assertEquals(event, store.read().getOrThrow())
    }

    @Test fun hostProtectionAndFeatureConfigurationHaveSeparateKeysAndStores() {
        val ui = SafeModeStore(File(temp.root, "ui"))
        val server = SafeModeStore(File(temp.root, "server"))
        assertTrue(ui.write(event))
        assertNull(server.read().getOrThrow())
        val featureKeys = FEATURES.flatMap { listOf(it.id) + it.options.map { option -> option.id } } + CONFIG_KEYS
        SCOPES.forEach {
            assertFalse(SafeModeKeys.disabled(it.id) in featureKeys)
            assertFalse(SafeModeKeys.reset(it.id) in featureKeys)
        }
        assertEquals(SCOPES.size, SCOPES.map { SafeModeKeys.disabled(it.id) }.toSet().size)
    }
}
