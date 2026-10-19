/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LauncherTrialGuardTest {
    @get:Rule val temporary = TemporaryFolder()

    private class MemoryJournal : LauncherTrialJournal {
        var state: LauncherTrialRecord? = null
        var fail = false
        override fun transaction(update: (LauncherTrialRecord?) -> LauncherTrialRecord): Result<LauncherTrialRecord> = runCatching {
            check(!fail) { "模拟读写失败" }
            update(state).also { state = it }
        }
    }

    @Test fun pendingTrialBlocksNextProcessAndDuplicateArm() {
        val store = MemoryJournal()
        val ticket = LauncherTrialGuard(store).arm(0).getOrThrow()
        assertTrue(ticket.pending)
        assertTrue(LauncherTrialGuard(store).arm(0).isFailure)
        assertEquals(ticket, store.state)
    }

    @Test fun onlyMatchingHealthTicketAllowsNextTrial() {
        val store = MemoryJournal()
        val guard = LauncherTrialGuard(store)
        val ticket = guard.arm(0).getOrThrow()
        assertTrue(guard.acknowledgeHealthy(ticket.copy(resetToken = 1)).isFailure)
        assertTrue(guard.acknowledgeHealthy(ticket).isSuccess)
        assertTrue(guard.acknowledgeHealthy(ticket).isFailure)
        assertTrue(guard.arm(0).isSuccess)
        assertTrue(guard.acknowledgeHealthy(ticket).isFailure)
    }

    @Test fun recoveryNeedsStrictlyNewerScopeSpecificToken() {
        val store = MemoryJournal()
        val guard = LauncherTrialGuard(store)
        guard.arm(5).getOrThrow()
        assertTrue(guard.arm(4).isFailure)
        assertTrue(guard.arm(5).isFailure)
        assertEquals(6L, guard.arm(6).getOrThrow().resetToken)
        assertTrue(guard.arm(-1).isFailure)
    }

    @Test fun ioFailureNeverProducesTicket() {
        val store = MemoryJournal().apply { fail = true }
        assertTrue(LauncherTrialGuard(store).arm(0).isFailure)
        assertNull(store.state)
    }

    @Test fun invalidRecordsAreNotSilentlyReset() {
        for (text in listOf("", "1|0|P", "2|0|P|x", "1|-1|P|x", "1|0|X|x", "garbage")) {
            assertTrue(runCatching { LauncherTrialRecord.decode(text) }.isFailure)
        }
        val record = LauncherTrialGuard(MemoryJournal()).arm(Long.MAX_VALUE).getOrThrow()
        assertEquals(record, LauncherTrialRecord.decode(record.encode()))
    }

    @Test fun fileJournalSurvivesNewGuardAndExplicitHealthAcknowledgement() {
        val directory = temporary.newFolder().toPath()
        var syncs = 0
        // 本地文件替换真实执行；目录 fsync 使用替身，不冒充 Android 文件系统验收。
        fun guard() = LauncherTrialGuard(LauncherTrialFileJournal(directory) { syncs++ })
        val ticket = guard().arm(0).getOrThrow()
        assertEquals(1, syncs)
        assertTrue(guard().arm(0).isFailure)
        assertTrue(guard().acknowledgeHealthy(ticket).isSuccess)
        assertTrue(guard().arm(0).isSuccess)
        assertEquals(3, syncs)
    }

    @Test fun corruptEmptyOrOversizedFileRemainsUntouchedEvenWithRecoveryToken() {
        val directory = temporary.newFolder().toPath()
        val path = directory.resolve("hyperextend_launcher_trial")
        for (contents in listOf("", "broken", "x".repeat(513))) {
            Files.write(path, contents.toByteArray())
            val guard = LauncherTrialGuard(LauncherTrialFileJournal(directory) {})
            assertTrue(guard.arm(Long.MAX_VALUE).isFailure)
            assertEquals(contents, String(Files.readAllBytes(path)))
        }
    }

    @Test fun missingDirectoryAndDirectoryAtRecordPathAreRejected() {
        val directory = temporary.newFolder().toPath()
        assertTrue(LauncherTrialGuard(LauncherTrialFileJournal(directory.resolve("missing")) {}).arm(0).isFailure)
        Files.createDirectory(directory.resolve("hyperextend_launcher_trial"))
        assertTrue(LauncherTrialGuard(LauncherTrialFileJournal(directory) {}).arm(0).isFailure)
    }

    @Test fun concurrentWriterCannotBypassFileLock() {
        val directory = temporary.newFolder().toPath()
        FileChannel.open(directory.resolve("hyperextend_launcher_trial.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                assertTrue(LauncherTrialGuard(LauncherTrialFileJournal(directory) {}).arm(0).isFailure)
            }
        }
        assertFalse(Files.exists(directory.resolve("hyperextend_launcher_trial")))
    }

    @Test fun failedDirectorySyncDoesNotGrantPermissionAndLeavesPendingEvidence() {
        val directory = temporary.newFolder().toPath()
        assertTrue(LauncherTrialGuard(LauncherTrialFileJournal(directory) { error("模拟 fsync 失败") }).arm(0).isFailure)
        assertTrue(LauncherTrialGuard(LauncherTrialFileJournal(directory) {}).arm(0).isFailure)
    }
}
