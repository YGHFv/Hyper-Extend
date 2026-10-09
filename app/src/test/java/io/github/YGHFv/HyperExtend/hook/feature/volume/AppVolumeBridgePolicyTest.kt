/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumeBridgePolicyTest {
    @Test fun appRatiosDoNotRequireAGlobalVolumeLevelOrUnmute() {
        assertNull(AppVolumeBridgePolicy.unavailableReason(locked = false, interactive = true, disabled = false))
    }
    @Test fun lockScreenAndFeatureDisableStillBlockDataAccess() {
        assertEquals("locked", AppVolumeBridgePolicy.unavailableReason(true, true, false))
        assertEquals("screen_off", AppVolumeBridgePolicy.unavailableReason(false, false, false))
        assertEquals("disabled", AppVolumeBridgePolicy.unavailableReason(false, true, true))
    }
    @Test fun nativeBallIsHiddenBeforeTheFirstAppSession() {
        assertTrue(AppVolumeBridgePolicy.hideNativeBall(ready = true, audited = true, isBall = true))
    }
    @Test fun unknownOrPartiallyInstalledHostKeepsNativeFallback() {
        assertFalse(AppVolumeBridgePolicy.hideNativeBall(ready = false, audited = true, isBall = true))
        assertFalse(AppVolumeBridgePolicy.hideNativeBall(ready = true, audited = false, isBall = true))
    }
    @Test fun nativeAppPanelAndUnrelatedWindowsAreNotHidden() {
        assertFalse(AppVolumeBridgePolicy.hideNativeBall(ready = true, audited = true, isBall = false))
    }
    private fun writable(uid: Int, host: Int = 1000, packages: List<String> = listOf("app"), pkg: String = "app") =
        AppVolumeBridgePolicy.writable(uid, host, packages, pkg)

    @Test fun ownUserApplicationIsWritable() { assertTrue(writable(10123)) }
    @Test fun systemAndIsolatedUidsAreRejected() {
        listOf(-1, 0, 1000, 9999, 20000, 99000, 99999).forEach { assertFalse(writable(it)) }
    }
    @Test fun anotherUserCannotBeWritten() { assertFalse(writable(1010123)) }
    @Test fun sameSecondaryUserIsAllowedButNotItsSystemUid() {
        assertTrue(writable(1010123, host = 1001000))
        assertFalse(writable(1001001, host = 1001000))
    }
    @Test fun sharedUidIsRejectedRatherThanChangingSiblings() {
        assertFalse(writable(10123, packages = listOf("app", "sibling")))
    }
    @Test fun UninstalledOrReassignedIdentityIsRejected() {
        assertFalse(writable(10123, packages = emptyList()))
        assertFalse(writable(10123, packages = listOf("replacement")))
    }
    @Test fun wallpaperIsRejected() {
        assertFalse(writable(10123, packages = listOf("com.miui.miwallpaper"), pkg = "com.miui.miwallpaper"))
    }
    @Test fun levelBoundsMatchNativeRatio() {
        assertTrue(AppVolumeBridgePolicy.levelValid(0))
        assertTrue(AppVolumeBridgePolicy.levelValid(1500))
        assertFalse(AppVolumeBridgePolicy.levelValid(-1))
        assertFalse(AppVolumeBridgePolicy.levelValid(1501))
    }
    @Test fun paginationKeepsFinalPartialPage() {
        assertEquals(0, AppVolumeBridgePolicy.pageCount(0, 3))
        assertEquals(1, AppVolumeBridgePolicy.pageCount(1, 3))
        assertEquals(1, AppVolumeBridgePolicy.pageCount(3, 3))
        assertEquals(2, AppVolumeBridgePolicy.pageCount(4, 3))
        assertEquals(7, AppVolumeBridgePolicy.pageCount(32, 5))
        assertEquals(Int.MAX_VALUE, AppVolumeBridgePolicy.pageCount(Int.MAX_VALUE, 0))
    }
    @Test fun unopenedLeaseNeverAcceptsNullToken() { assertFalse(AppVolumeBridgeLease().accepts(null, 0)) }
    @Test fun leaseHasStrictExpiryAndRejectsClockReversal() {
        val lease = AppVolumeBridgeLease().apply { open("first", 100) }
        assertFalse(lease.accepts("first", 99))
        assertTrue(lease.accepts("first", 10099))
        assertFalse(lease.accepts("first", 10100))
    }
    @Test fun closeInvalidatesPendingReply() {
        val lease = AppVolumeBridgeLease().apply { open("first", 0); close() }
        assertFalse(lease.accepts("first", 1))
        assertFalse(lease.write("first", 1, 1))
    }
    @Test fun newSessionRejectsOldSessionAndResetsSequence() {
        val lease = AppVolumeBridgeLease().apply { open("first", 0) }
        assertTrue(lease.write("first", 20, 1))
        lease.open("second", 2)
        assertFalse(lease.write("first", 21, 3))
        assertTrue(lease.write("second", 0, 3))
    }
    @Test fun duplicateAndOutOfOrderWritesAreRejected() {
        val lease = AppVolumeBridgeLease().apply { open("first", 0) }
        assertFalse(lease.write("first", -1, 1))
        assertTrue(lease.write("first", 3, 1))
        assertFalse(lease.write("first", 3, 2))
        assertFalse(lease.write("first", 2, 2))
        assertTrue(lease.write("first", 4, 2))
    }
    @Test fun validActivityRenewsLeaseButWrongTokenDoesNot() {
        val lease = AppVolumeBridgeLease().apply { open("first", 0) }
        assertFalse(lease.write("wrong", 1, 9000))
        assertFalse(lease.accepts("first", 10000))
        lease.open("second", 10000)
        assertTrue(lease.write("second", 1, 19000))
        assertTrue(lease.accepts("second", 28000))
        lease.touch(28000)
        assertTrue(lease.accepts("second", 37000))
    }
}
