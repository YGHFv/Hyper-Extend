/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.YGHFv.HyperExtend.core.PlannedRestartRequest
import io.github.YGHFv.HyperExtend.core.PlannedRestarts

class LauncherSafetyMaintenanceTest {
    private val now = 1234L

    @Test fun rootRequestUsesOnlyTheRequestFileAndRetainsTheFuse() {
        val script = LauncherSafetyMaintenance.rootRequestScript(10, now, 77, true)
        assertTrue(script.contains("/data/user/10/com.miui.home/files"))
        assertTrue(script.contains("hyperextend_launcher_restart.request"))
        assertTrue(script.contains("chmod 0444"))
        assertTrue(script.contains("mv -fT"))
        assertTrue(!script.contains("bootguard") && !script.contains("rm "))
        assertTrue(runCatching { LauncherSafetyMaintenance.rootRequestScript(-1, now, 77, true) }.isFailure)
        assertTrue(runCatching { LauncherSafetyMaintenance.rootRequestScript(0, now, 77, false) }.isFailure)
    }
    @Test fun deviceRestartIntentIsReissuedForTheNewBootAndExpiresByWallTime() {
        val wall = 1_700_000_000_000L
        val key = PlannedRestarts.key("launcher")
        val raw = PlannedRestartRequest(wall, wall, wall + 180000).encode()
        val read: (String) -> Any? = { if (it == key) raw else null }
        val first = LauncherSafetyMaintenance.pending(read, 20, wall + 60000)!!
        val next = LauncherSafetyMaintenance.pending(read, 21, wall + 60001)!!
        assertEquals(key, first.key)
        assertEquals(raw, first.stored)
        assertEquals(LauncherSafetyMaintenance.parseRequest(first.request, 1, 21),
            LauncherSafetyMaintenance.parseRequest(next.request, 1, 21))
        assertNull(LauncherSafetyMaintenance.pending(read, 20, wall + 180000))
        assertNull(LauncherSafetyMaintenance.pending(read, 20, wall - 1))
    }

    @Test fun plannedRestartAndRecoveryUseDifferentAuthorizedOperations() {
        val planned = LauncherSafetyMaintenance.restartRequest(now, 77, false)
        assertEquals(1, LauncherSafetyMaintenance.operationForRequest(planned))
        assertEquals(LauncherSafetyMaintenance.PLANNED_RESTART_TOKEN_TAG or 77,
            LauncherSafetyMaintenance.parseRequest(planned, 1, now))
        assertNull(LauncherSafetyMaintenance.parseRequest(planned, 2, now))
        val recovery = LauncherSafetyMaintenance.restartRequest(now, 88, true)
        assertEquals(2, LauncherSafetyMaintenance.operationForRequest(recovery))
        assertEquals(88, LauncherSafetyMaintenance.parseRequest(recovery, 2, now))
        assertNull(LauncherSafetyMaintenance.parseRequest(recovery, 1, now))
    }

    @Test fun plannedRestartRejectsExpiredRequestAndInvalidTokenDomain() {
        val request = LauncherSafetyMaintenance.restartRequest(now, 77, false)
        assertNull(LauncherSafetyMaintenance.parseRequest(request, 1, now + 31))
        for (token in listOf(0, -1, LauncherSafetyMaintenance.PLANNED_RESTART_TOKEN_TAG, Int.MAX_VALUE)) {
            assertTrue(runCatching { LauncherSafetyMaintenance.restartRequest(now, token, false) }.isFailure)
        }
    }

    @Test fun explicitRecoveryTokensRemainMonotonicWithinOneSecondAndAfterClockRollback() {
        assertEquals(100, LauncherSafetyMaintenance.nextRecoveryToken(100, 0))
        assertEquals(101, LauncherSafetyMaintenance.nextRecoveryToken(100, 100))
        assertEquals(102, LauncherSafetyMaintenance.nextRecoveryToken(99, 101))
        assertTrue(runCatching { LauncherSafetyMaintenance.nextRecoveryToken(100, Int.MAX_VALUE.toLong()) }.isFailure)
    }

    @Test fun parsesOnlyTheCurrentVersionAndSupportedOperation() {
        assertEquals(77, LauncherSafetyMaintenance.parseRequest("1|1|1234|77", 1, now))
        assertEquals(88, LauncherSafetyMaintenance.parseRequest("1|2|1234|88", 2, now))
        assertNull(LauncherSafetyMaintenance.parseRequest("2|1|1234|77", 1, now))
        assertNull(LauncherSafetyMaintenance.parseRequest("1|0|1234|77", 1, now))
        assertNull(LauncherSafetyMaintenance.parseRequest("1|3|1234|77", 3, now))
    }

    @Test fun rejectsMalformedFieldsAndIntegerBoundaries() {
        val invalid = listOf(
            null,
            123,
            "",
            "1|1|1234",
            "1|1|1234|77|extra",
            "x|1|1234|77",
            "1|x|1234|77",
            "1|1|x|77",
            "1|1|1234|x",
            "1|1|0|77",
            "1|1|1234|0",
            "1|1|1234|2147483648",
            "1|1|2147483648|77",
        )
        for (raw in invalid) assertNull(LauncherSafetyMaintenance.parseRequest(raw, 1, now))
        assertNull(LauncherSafetyMaintenance.parseRequest("1|1|1234|77", 1, 0))
        assertNull(LauncherSafetyMaintenance.parseRequest("1|1|1234|77", 1, Long.MAX_VALUE))
    }

    @Test fun rejectsFutureAndExpiredRequestsButAcceptsLeaseEdges() {
        assertNull(LauncherSafetyMaintenance.parseRequest("1|1|1235|77", 1, now))
        assertEquals(77, LauncherSafetyMaintenance.parseRequest("1|1|1204|77", 1, now))
        assertNull(LauncherSafetyMaintenance.parseRequest("1|1|1203|77", 1, now))
    }

    @Test fun operationExtractionDoesNotAuthorizeMalformedRequests() {
        assertEquals(1, LauncherSafetyMaintenance.operationForRequest("1|1|not-a-time|not-a-token"))
        assertEquals(2, LauncherSafetyMaintenance.operationForRequest("1|2|1234|88"))
        assertNull(LauncherSafetyMaintenance.operationForRequest("2|1|1234|77"))
        assertNull(LauncherSafetyMaintenance.operationForRequest("1|3|1234|77"))
        assertNull(LauncherSafetyMaintenance.operationForRequest("1|1"))
        assertTrue(LauncherSafetyMaintenance.operationForRequest("") == null)
    }
}
