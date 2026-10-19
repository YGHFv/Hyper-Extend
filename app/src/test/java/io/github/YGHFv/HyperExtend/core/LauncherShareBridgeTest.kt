/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.hook.feature.launcher.LauncherSnapshotWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherShareBridgeTest {

    @Test fun packagePolicyAcceptsOnlyCanonicalPackageNames() {
        assertEquals("com.tencent.mobileqq",
            LauncherShareBridge.packagePolicy("com.tencent.mobileqq"))
        assertEquals("a", LauncherShareBridge.packagePolicy("a"))
        assertEquals("a_b-c.d0", LauncherShareBridge.packagePolicy("a_b-c.d0"))

        assertNull(LauncherShareBridge.packagePolicy(null))
        assertNull(LauncherShareBridge.packagePolicy(""))
        assertNull(LauncherShareBridge.packagePolicy("Com.Example"))
        assertNull(LauncherShareBridge.packagePolicy("1com.example"))
        assertNull(LauncherShareBridge.packagePolicy("com/example"))
        assertNull(LauncherShareBridge.packagePolicy(".com.example"))
        assertNull(LauncherShareBridge.packagePolicy("com..example"))
        assertNull(LauncherShareBridge.packagePolicy("com.example."))
        assertNull(LauncherShareBridge.packagePolicy("com.example\n"))
        assertNull(LauncherShareBridge.packagePolicy("a".repeat(
            LauncherShareBridge.MAX_PACKAGE_LENGTH + 1,
        )))
    }

    @Test fun splitLabelsAreSafeAndHaveFallbacks() {
        val labels = LauncherShareBridge.splitLabels(
            arrayOf("config.arm64", null, "///", "feature_1"),
        )
        assertEquals(listOf("configarm64", "split_1", "split_2", "feature_1"), labels)
        assertEquals("split_0", LauncherShareBridge.splitLabels(arrayOf("///")).single())
        assertTrue(LauncherShareBridge.splitLabels(null).isEmpty())
    }

    @Test fun outputNamesAreBoundedAndExtensionMatchesPayload() {
        val apk = LauncherShareBridge.outputName("com.example", 123, true)
        val apks = LauncherShareBridge.outputName("com.example", 456, false)
        assertEquals("com.example-123.apk", apk)
        assertEquals("com.example-456.apks", apks)
        assertTrue(apk.endsWith(".apk"))
        assertTrue(apks.endsWith(".apks"))
        assertFalse(apk.contains('/'))
        assertFalse(apks.contains('/'))
    }

    @Test fun longPackageNamesStillProduceValidBoundedFileNames() {
        val packageName = "a" + "b".repeat(LauncherShareBridge.MAX_PACKAGE_LENGTH - 1)
        val name = LauncherShareBridge.outputName(
            packageName,
            Long.MAX_VALUE,
            singleApk = false,
        )
        assertTrue(name.length <= 255)
        assertTrue(name.endsWith(".apks"))
        assertTrue(name.contains("-9223372036854775807.apks"))
    }

    @Test fun shareWireDoesNotCollideWithSnapshotOrMaintenance() {
        assertEquals(0x00484543, LauncherSnapshotWire.SHARE_TRANSACTION)
        assertNotEquals(LauncherSnapshotWire.SHARE_TRANSACTION,
            LauncherSnapshotWire.TRANSACTION)
        assertNotEquals(LauncherSnapshotWire.SHARE_TRANSACTION,
            LauncherSnapshotWire.MAINTENANCE_TRANSACTION)
        assertEquals(0x48455332, LauncherSnapshotWire.SHARE_QUEUED_ACK)
        assertNotEquals(LauncherSnapshotWire.SHARE_QUEUED_ACK,
            LauncherSnapshotWire.SHARE_ACK)
        assertNotEquals(LauncherSnapshotWire.SHARE_QUEUED_ACK,
            LauncherSnapshotWire.SHARE_REJECTED_ACK)
    }
}
