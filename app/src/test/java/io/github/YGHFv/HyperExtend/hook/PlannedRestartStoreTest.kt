/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.core.PlannedRestartRequest
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlannedRestartStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 1_700_000_000_000L
    private fun store(file: File) = PlannedRestartStore(file) { }

    @Test fun requestIsConsumedOnceAcrossNewStoreInstances() {
        val file = File(temporary.root, "consumed")
        val request = PlannedRestartRequest(now, now, now + 30000).encode()
        assertTrue(store(file).consume(request, now))
        assertFalse(store(file).consume(request, now + 1))
        assertEquals(now.toString(), file.readText())
    }

    @Test fun expiredFutureAndMalformedRequestsDoNotExemptRestarts() {
        val store = store(File(temporary.root, "consumed"))
        for (request in listOf(null, "broken",
            PlannedRestartRequest(now, now + 1, now + 30000).encode(),
            PlannedRestartRequest(now, now - 30000, now).encode(),
            PlannedRestartRequest(now, now, now + 180001).encode())) {
            assertFalse(store.consume(request, now))
        }
    }

    @Test fun consumingAnIntentDoesNotRecoverAFuseAndCrashesStillAccumulate() {
        val store = store(File(temporary.root, "consumed"))
        val raw = PlannedRestartRequest(now, now, now + 30000).encode()
        val fused = BootFuseState(now, 3, true, 0, now)
        assertTrue(BootFusePolicy.next(fused, now, 0, !store.consume(raw, now))!!.disabled)
        var state = BootFuseState(now, 0, false, 0, now)
        repeat(3) { state = BootFusePolicy.next(state, now + 1, 0, !store.consume(raw, now + 1))!! }
        assertTrue(state.disabled)
    }

    @Test fun corruptAcknowledgementOrPendingWriteDoesNotCreateAnExemption() {
        val file = File(temporary.root, "consumed")
        val raw = PlannedRestartRequest(now, now, now + 30000).encode()
        file.writeText("damaged")
        assertTrue(runCatching { store(file).consume(raw, now) }.isFailure)
        file.writeText("0")
        File(temporary.root, "consumed.tmp").writeText("pending")
        assertTrue(runCatching { store(file).consume(raw, now) }.isFailure)
        assertEquals("0", file.readText())
    }

    @Test fun directorySyncFailureDoesNotIssueAnExemption() {
        val file = File(temporary.root, "consumed")
        val raw = PlannedRestartRequest(now, now, now + 30000).encode()
        val broken = PlannedRestartStore(file) { error("模拟目录同步失败") }
        assertTrue(runCatching { broken.consume(raw, now) }.isFailure)
        assertFalse(store(file).consume(raw, now))
    }
}
