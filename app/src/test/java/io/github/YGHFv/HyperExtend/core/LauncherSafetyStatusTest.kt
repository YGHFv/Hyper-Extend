/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class LauncherSafetyStatusTest {
    @Test fun nativeFuseIsVisibleEvenWithoutAJavaIncident() {
        val status = LauncherSafetyStatusReader.decode("state\n1700000001000,3,1,0,1700000000000")
        assertEquals(LauncherSafetyState.BLOCKED, status.status)
        assertTrue(status.blocked)
        assertNotNull(status.incidentId)
        assertTrue(status.reason!!.contains("锁存"))
        assertEquals(3, status.state!!.rapidRestarts)
        assertEquals(status, LauncherSafetyStatusReader.decode("state\r\n1700000001000,3,1,0,1700000000000\r\n"))
    }

    @Test fun recoveryIsVisibleOnlyAfterHealthyNativeStateIsReadBack() {
        val status = LauncherSafetyStatusReader.decode("state\n1700000002000,0,0,51,1700000002000")
        assertEquals(LauncherSafetyState.HEALTHY, status.status)
        assertFalse(status.blocked)
        assertNull(status.incidentId)
        assertEquals(51L, status.state!!.lastResetToken)
    }

    @Test fun unknownDamagedAndPendingHaveDistinctStates() {
        for (raw in listOf(null, "unavailable")) {
            val status = LauncherSafetyStatusReader.decode(raw)
            assertEquals(LauncherSafetyState.UNKNOWN, status.status)
            assertFalse(status.blocked)
            assertTrue(LauncherSafetyPolicy.canReleaseManualDisable(status))
            assertEquals(LauncherManualRelease.ALLOWED, LauncherSafetyPolicy.release(status))
        }
        assertEquals(LauncherSafetyState.PENDING, LauncherSafetyStatusReader.decode("pending").status)
        assertEquals(LauncherManualRelease.BLOCKED_BY_RESIDUE,
            LauncherSafetyPolicy.release(LauncherSafetyStatusReader.decode("pending")))
        for (raw in listOf("invalid", "state\nbroken", "state\n0,4,0,0,0",
            "state\n" + "0".repeat(129))) {
            assertEquals(LauncherSafetyState.DAMAGED, LauncherSafetyStatusReader.decode(raw).status)
            assertEquals(LauncherManualRelease.BLOCKED_BY_RESIDUE,
                LauncherSafetyPolicy.release(LauncherSafetyStatusReader.decode(raw)))
        }
        assertEquals(LauncherSafetyState.ABSENT, LauncherSafetyStatusReader.decode("absent").status)
        assertFalse(LauncherSafetyPolicy.masksModule(LauncherSafetyStatusReader.decode("absent")))
        assertTrue(LauncherSafetyStatusReader.decode("state\n1700000001000,3,0,0,1700000000000").blocked)
    }

    @Test fun onlyACompleteBlockedStateUsesNativeRecovery() {
        val healthy = LauncherSafetyStatusReader.decode("state\n1700000002000,0,0,51,1700000002000")
        val blocked = LauncherSafetyStatusReader.decode("state\n1700000001000,3,1,0,1700000000000")
        val damaged = LauncherSafetyStatusReader.decode("state\nbroken")
        assertFalse(LauncherSafetyPolicy.requiresNativeRecovery(healthy))
        assertTrue(LauncherSafetyPolicy.requiresNativeRecovery(blocked))
        assertFalse(LauncherSafetyPolicy.requiresNativeRecovery(damaged))
        assertTrue(LauncherSafetyPolicy.canReleaseManualDisable(null))
        assertFalse(LauncherSafetyPolicy.canReleaseManualDisable(blocked))
        assertEquals(LauncherManualRelease.NATIVE_RECOVERY_REQUIRED,
            LauncherSafetyPolicy.release(blocked))
    }

    @Test fun recoveryRequiresHealthyReadbackAndANewerResetToken() {
        val before = LauncherSafetyStatusReader.decode("state\n1700000001000,3,1,0,1700000000000").state
        val sameToken = LauncherSafetyStatusReader.decode("state\n1700000002000,0,0,51,1700000002000")
        val advanced = LauncherSafetyStatusReader.decode("state\n1700000003000,0,0,52,1700000003000")
        assertFalse(LauncherSafetyPolicy.recovered(sameToken, before?.copy(lastResetToken = 51)))
        assertTrue(LauncherSafetyPolicy.recovered(advanced, before?.copy(lastResetToken = 51)))
        assertFalse(LauncherSafetyPolicy.recovered(
            LauncherSafetyStatusReader.decode("pending"), before))
    }

    @Test fun manualSafetyModeMasksProjectionWithoutChangingTheUserConfiguration() {
        for (configured in listOf(false, true)) {
            assertFalse(SafeModeProjection.launcherEnabled(configured, true))
            assertEquals(configured, SafeModeProjection.launcherEnabled(configured, false))
        }
    }
}
