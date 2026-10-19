/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LauncherConfigurationTest {
    private val evidence = LauncherProcessEvidence(100, 10001, 10001, "com.miui.home", "com.miui.home",
        801027726L, postSpecializationConfirmed = true, spawner = false)
    private val base = mapOf<String, Any>(LauncherConfiguration.PROTOCOL to 1)

    private fun prepare(values: Map<String, *>? = base) = LauncherConfiguration.prepare(evidence, { values }, 1, 1000)

    @Test fun allMissingSwitchesAreOff() {
        val packet = prepare().getOrThrow()
        assertEquals(0, packet.features)
        assertEquals(20, packet.countLimit)
        assertArrayEquals(longArrayOf(1, 100, 1, 1000, 31000, 0, 20), packet.toWire())
    }

    @Test fun missingRegisteredOptionsUseCatalogDefaults() {
        val packet = prepare(base + mapOf(LauncherConfiguration.MASTER to true)).getOrThrow()
        // 数量选项在功能目录中默认开启，分享 APK 与小窗默认关闭。
        assertEquals(1, packet.features)
        assertEquals(20, packet.countLimit)
    }

    @Test fun childSwitchesCannotBypassMaster() {
        val keys = listOf(LauncherConfiguration.COUNT, LauncherConfiguration.SHARE,
            LauncherConfiguration.FREEFORM, LauncherConfiguration.TASKS)
        assertEquals(0, prepare(base + keys.associateWith { true }).getOrThrow().features)
        keys.forEachIndexed { bit, key ->
            val switches = keys.associateWith { false } + (key to true)
            assertEquals(1 shl bit, prepare(base + mapOf(LauncherConfiguration.MASTER to true) + switches).getOrThrow().features)
        }
        assertEquals(15, prepare(base + keys.associateWith { true } + (LauncherConfiguration.MASTER to true)).getOrThrow().features)
    }

    @Test fun missingSnapshotOrProtocolNeverBecomesDefaultConfiguration() {
        for (values in listOf(null, emptyMap(), mapOf(LauncherConfiguration.PROTOCOL to 2),
            mapOf(LauncherConfiguration.PROTOCOL to "1"), mapOf(LauncherConfiguration.PROTOCOL to 1L))) {
            assertTrue(prepare(values).isFailure)
        }
    }

    @Test fun malformedTypesAndRangesAreNotCoerced() {
        for (key in listOf(LauncherConfiguration.MASTER, LauncherConfiguration.COUNT, LauncherConfiguration.SHARE,
            LauncherConfiguration.FREEFORM, LauncherConfiguration.TASKS)) {
            for (value in listOf(null, 1, "true")) assertTrue(prepare(base + (key to value)).isFailure)
        }
        for (value in listOf(4, 21, 40, 5L, "5", null)) {
            assertTrue(prepare(base + (LauncherConfiguration.COUNT_LIMIT to value)).isFailure)
        }
        for (value in listOf(5, 20)) assertEquals(value,
            prepare(base + (LauncherConfiguration.COUNT_LIMIT to value)).getOrThrow().countLimit)
    }

    @Test fun unknownOrSpawnerIdentityNeverReadsPreferences() {
        val invalid = listOf(LauncherProcessEvidence(), evidence.copy(pid = 0), evidence.copy(uid = 1000),
            evidence.copy(packageUid = 10002), evidence.copy(packageName = "other"),
            evidence.copy(processName = "com.miui.home:remote"),
            evidence.copy(postSpecializationConfirmed = false), evidence.copy(spawner = true))
        invalid.forEach { identity ->
            var calls = 0
            assertTrue(LauncherConfiguration.prepare(identity, { calls++; base }, 1, 1000).isFailure)
            assertEquals(0, calls)
        }
    }

    @Test fun invalidRevisionOrClockFailsBeforeRead() {
        for ((revision, time) in listOf(0L to 1000L, -1L to 1000L, 0x100000000L to 1000L,
            1L to -1L, 1L to Long.MAX_VALUE)) {
            var reads = 0
            assertTrue(LauncherConfiguration.prepare(evidence, { reads++; base }, revision, time).isFailure)
            assertEquals(0, reads)
        }
    }

    @Test fun readFailureDoesNotReuseLastGoodSnapshot() {
        assertTrue(prepare().isSuccess)
        assertTrue(LauncherConfiguration.prepare(evidence, { error("模拟失联") }, 2, 1000).isFailure)
        assertTrue(prepare(null).isFailure)
    }

    @Test fun preferencesAreReadInOneSnapshotWithoutPerKeyFallback() {
        var reads = 0
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            check(method.name == "getAll") { "不应逐键读取" }
            reads++
            base
        } as SharedPreferences
        assertTrue(LauncherConfiguration.prepareFromPreferences(evidence, prefs, 1, 1000).isSuccess)
        assertEquals(1, reads)
    }

    @Test fun wireArrayCannotMutateConfiguration() {
        val packet = prepare().getOrThrow()
        packet.toWire()[5] = 15
        assertEquals(0, packet.toWire()[5])
    }

    @Test fun preflightResultRequiresKnownUnverifiedPermissionBits() {
        for (bits in listOf(0x700, 0x720)) {
            val decision = LauncherConfigurationDecision(bits)
            assertTrue(decision.configurationValid)
            assertTrue(decision.hostUnverified && decision.safetyUnverified && decision.backendUnverified)
        }
        assertTrue(LauncherConfigurationDecision(0x720).allFeaturesOff)
        for (bits in listOf(0, 1, -1, 0x701, 0x740, 0x780, 0x1700)) {
            assertFalse(LauncherConfigurationDecision(bits).configurationValid)
        }
    }
}
