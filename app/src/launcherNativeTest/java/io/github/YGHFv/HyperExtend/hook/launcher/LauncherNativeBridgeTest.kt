/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherNativeBridgeTest {
    @Test fun initializingBridgeDoesNotLoadAndroidLibrary() {
        // 本地 JVM 没有 ARM64 库，初始化对象必须仍然成功。
        assertNotNull(LauncherNativeBridge)
        for (name in listOf("nativeStatus", "nativeApiVersion")) {
            assertTrue(Modifier.isNative(LauncherNativeBridge::class.java.getDeclaredMethod(name).modifiers))
        }
        assertTrue(Modifier.isNative(LauncherNativeBridge::class.java.getDeclaredMethod("nativeBeginConfiguration").modifiers))
        assertTrue(Modifier.isNative(LauncherNativeBridge::class.java.getDeclaredMethod("nativeCheckConfiguration", LongArray::class.java).modifiers))
        for (name in listOf("nativeQueryConfiguration", "nativeChannelStatus",
            "nativeSnapshotRevision", "nativeSnapshotFeatures")) {
            assertTrue(Modifier.isNative(LauncherNativeBridge::class.java.getDeclaredMethod(name).modifiers))
        }
    }

    @Test fun loadingJniDoesNotImplyFrameworkCallback() {
        val status = LauncherNativeBridge.DiagnosticStatus(1 or 2, -1)
        assertTrue(status.jniLoaded)
        assertFalse(status.nativeCallbackSeen)
        assertFalse(status.inheritedState)
    }

    @Test fun callbackWithoutEntriesIsRejectedAsEntryEvidence() {
        val status = LauncherNativeBridge.DiagnosticStatus(1 or 2 or 4 or 8, 102)
        assertTrue(status.nativeCallbackSeen)
        assertTrue(status.entriesRejected)
        assertFalse(status.entriesVerified)
    }

    @Test fun verifiedEntriesStillDoNotPermitHostSafetyOrBackend() {
        // 位 0x800 只表示入口结构可用；它不携带宿主、安全或后端许可位。
        val status = LauncherNativeBridge.DiagnosticStatus(1 or 2 or 4 or 0x800, 2)
        assertTrue(status.nativeCallbackSeen)
        assertTrue(status.entriesVerified)
        assertFalse(status.entriesRejected)
        assertEquals(2L, status.observedFrameworkVersion)
    }

    @Test fun inheritedStateIsNotCurrentProcessEvidence() {
        val status = LauncherNativeBridge.DiagnosticStatus(1 or 16, -1)
        assertTrue(status.inheritedState)
        assertFalse(status.jniLoaded)
        assertFalse(status.nativeCallbackSeen)
    }

    /** 通道阻断位各自独立，且「接受」只由全零决定，不携带任何安装授权含义。 */
    @Test fun channelBlockersAreIndependentAndAcceptanceIsZeroOnly() {
        val accepted = LauncherNativeBridge.ConfigurationProbe(0, 0, 7, 1)
        assertTrue(accepted.accepted)
        assertFalse(accepted.noBinder)
        assertFalse(accepted.snapshotRejected)

        val cases = listOf(
            1 to { p: LauncherNativeBridge.ConfigurationProbe -> p.noBinder },
            2 to { p: LauncherNativeBridge.ConfigurationProbe -> p.noWindowService },
            4 to { p: LauncherNativeBridge.ConfigurationProbe -> p.transactFailed },
            8 to { p: LauncherNativeBridge.ConfigurationProbe -> p.shortReply },
            16 to { p: LauncherNativeBridge.ConfigurationProbe -> p.snapshotRejected },
        )
        for ((bit, check) in cases) {
            val probe = LauncherNativeBridge.ConfigurationProbe(bit, bit, 0, 0)
            assertFalse("位 $bit 不得被当作接受", probe.accepted)
            assertTrue("位 $bit 未被识别", check(probe))
        }
    }
}
