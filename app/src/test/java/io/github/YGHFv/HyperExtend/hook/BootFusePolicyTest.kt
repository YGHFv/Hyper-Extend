/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BootFusePolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = BootFusePolicy.MIN_TIME + 1000000

    @Test fun fourthStartTripsAndLatchSurvivesLongWait() {
        var state = BootFuseState()
        repeat(4) { i ->
            state = BootFusePolicy.next(state, now + i * 60000, 0, true)!!
            assertEquals(i == 3, state.disabled)
        }
        assertTrue(BootFusePolicy.next(state, now + 86400000, 0, true)!!.disabled)
    }
    @Test fun windowIsNotExtendedByEveryRestart() {
        var state = BootFusePolicy.next(BootFuseState(), now, 0, true)!!
        repeat(3) { state = BootFusePolicy.next(state, state.lastStartAt + 240000, 0, true)!! }
        assertFalse(state.disabled)
        assertEquals(1, state.rapidRestarts)
    }
    @Test fun exactWindowBoundaryIsIncluded() {
        val state = BootFuseState(now + 200000, 2, false, 0, now)
        assertTrue(BootFusePolicy.next(state, now + 300000, 0, true)!!.disabled)
        assertEquals(0, BootFusePolicy.next(state, now + 300001, 0, true)!!.rapidRestarts)
    }
    @Test fun resetIsOneShotAndProtectionRemainsActive() {
        val tripped = BootFuseState(now, 3, true, 10, now)
        var state = BootFusePolicy.next(tripped, now + 100, 11, true)!!
        assertFalse(state.disabled)
        assertEquals(0, state.rapidRestarts)
        repeat(3) { state = BootFusePolicy.next(state, state.lastStartAt + 100, 11, true)!! }
        assertTrue(state.disabled)
    }
    @Test fun noRestartDoesNotIncrementCounter() {
        val state = BootFuseState(now, 1, false, 0, now)
        assertEquals(state, BootFusePolicy.next(state, now + 10, 0, false))
    }
    @Test fun badClockAndUnreadableStateFailClosed() {
        assertNull(BootFusePolicy.next(null, now, 0, true))
        assertNull(BootFusePolicy.next(BootFuseState(), 0, 0, true))
        assertNull(BootFusePolicy.next(BootFuseState(now), now - 1, 0, true))
    }
    @Test fun legacyStateIsPreservedAndNewStateRoundTrips() {
        assertEquals(BootFuseState(now, 1, false, 5, now), BootFuseState.decode("$now,1,0,5"))
        assertTrue(BootFuseState.decode("$now,3,0,5")!!.disabled)
        val state = BootFuseState(now + 50, 2, false, 10, now)
        assertEquals(state, BootFuseState.decode(state.encode()))
    }
    @Test fun invalidStateCannotSilentlyResetCounters() {
        for (text in listOf("", "0", "abc,0", "-1,0", "0,-1", "0,2147483647", "0,0,2", "0,0,0,bad", "0,0,0,0,10")) {
            assertNull(text, BootFuseState.decode(text))
        }
    }
    @Test fun storePersistsAndRejectsCorruption() {
        val file = File(temp.root, "state")
        val store = BootFuseStore(file)
        assertEquals(BootFuseState(), store.read())
        val state = BootFuseState(now, 3, true, 1, now)
        assertTrue(store.write(state))
        assertEquals(state, store.read())
        assertFalse(File(temp.root, "state.tmp").exists())
        file.writeText("truncated")
        assertNull(store.read())
    }
    @Test fun failedReplacementKeepsLastConfirmedState() {
        val file = File(temp.root, "state")
        val store = BootFuseStore(file)
        val previous = BootFuseState(now, 2, false, 1, now)
        assertTrue(store.write(previous))
        assertTrue(File(temp.root, "state.tmp").mkdir())
        assertFalse(store.write(previous.copy(disabled = true)))
        assertEquals(previous, store.read())
    }
    @Test fun systemUiAndServerUseIndependentStores() {
        val ui = BootFuseStore(File(temp.root, "ui"))
        val server = BootFuseStore(File(temp.root, "server"))
        assertTrue(ui.write(BootFuseState(now, 3, true, 0, now)))
        assertFalse(server.read()!!.disabled)
    }
}
