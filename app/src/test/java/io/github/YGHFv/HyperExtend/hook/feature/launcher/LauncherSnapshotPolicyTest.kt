/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import io.github.YGHFv.HyperExtend.core.FRAMEWORK_FUSE_RESET_KEY
import io.github.YGHFv.HyperExtend.core.SafeModeKeys
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class LauncherSnapshotPolicyTest {

    @Test fun catalogExposesTheProductionCountSetting() {
        val feature = io.github.YGHFv.HyperExtend.core.featureById(LauncherSnapshotWire.MASTER)
        assertNotNull(feature)
        assertEquals(listOf("launcher"), feature!!.scopes)
        assertEquals(LauncherSnapshotWire.COUNT_LIMIT, feature.config.single().key)
        assertEquals(
            setOf(LauncherSnapshotWire.COUNT, LauncherSnapshotWire.SHARE,
                LauncherSnapshotWire.FREEFORM),
            feature.options.map { it.id }.toSet(),
        )
    }

    private fun cmdline(text: String): ByteArray =
        (text.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0, 0))

    @Test fun callerMustBeAnAppUidWithTheExactLauncherCmdline() {
        assertTrue(isLauncherCaller(10139, 27615, cmdline("com.miui.home")))
        // 系统身份（system_server 自己）不得通过本通道取桌面配置。
        assertFalse(isLauncherCaller(1000, 3641, cmdline("com.miui.home")))
        assertFalse(isLauncherCaller(0, 1, cmdline("com.miui.home")))
        // 前缀相同、后缀不同的进程名不能被当成桌面。
        assertFalse(isLauncherCaller(10139, 2, cmdline("com.miui.home:remote")))
        assertFalse(isLauncherCaller(10139, 2, cmdline("com.miui.homework")))
        // cmdline 未以 NUL 结束、为空、pid/uid 非法都要拒绝。
        assertFalse(isLauncherCaller(10139, 2, "com.miui.home".toByteArray(StandardCharsets.US_ASCII)))
        assertFalse(isLauncherCaller(10139, 2, ByteArray(0)))
        assertFalse(isLauncherCaller(10139, 2, null))
        assertFalse(isLauncherCaller(10139, 0, cmdline("com.miui.home")))
        assertFalse(isLauncherCaller(10139, -1, cmdline("com.miui.home")))
    }

    @Test fun wireConstantsMatchTheReviewedChannels() {
        // 事务码不得与上游 HyperCeiler 的 0x0048434A..0x0048434D 重叠。
        assertEquals(0x00484541, LauncherSnapshotWire.TRANSACTION)
        for (other in 0x0048434A..0x0048434D) assertNotEquals(other, LauncherSnapshotWire.TRANSACTION)
        // 与原生 launcher/channel.h、launcher/snapshot_wire.h 的字面量一致。
        assertEquals(0x48453431, LauncherSnapshotWire.ACK)
        assertEquals(1, LauncherSnapshotWire.PROTOCOL)
        assertEquals(10, LauncherSnapshotWire.WORDS)
        assertEquals(30, LauncherSnapshotWire.LEASE_SECONDS)
    }

    @Test fun deniedReplyIsParsableButNeverArmed() {
        val words = LauncherSnapshotWire.denied()
        assertEquals(LauncherSnapshotWire.WORDS, words.size)
        assertEquals(LauncherSnapshotWire.ACK, words[0])
        assertEquals(LauncherSnapshotWire.PROTOCOL, words[1])
        // 第 8 个字是「已武装」；但序号与上限也为 0，原生侧会先按畸形拒绝。
        assertEquals(0, words[7])
    }

    @Test fun masterSwitchGatesEveryFeatureBit() {
        val on = mapOf(
            LauncherSnapshotWire.MASTER to true,
            LauncherSnapshotWire.COUNT to true, LauncherSnapshotWire.SHARE to true,
            LauncherSnapshotWire.FREEFORM to true, LauncherSnapshotWire.TASKS to true,
            LauncherSnapshotWire.COUNT_LIMIT to 9,
        )
        val armedSnapshot = buildLauncherSnapshot(1, 1000) { on[it] }
        assertNotNull(armedSnapshot)
        assertEquals(LauncherSnapshotWire.FEATURE_MASK, armedSnapshot!!.features)
        assertTrue(armedSnapshot.armed)
        assertEquals(9, armedSnapshot.countLimit)

        // 子项全开但主开关关着：功能位必须为 0，且判为「未武装」。
        val noMaster = on - LauncherSnapshotWire.MASTER
        val off = buildLauncherSnapshot(1, 1000) { noMaster[it] }!!
        assertEquals(0, off.features)
        assertFalse(off.armed)
    }

    @Test fun malformedSettingTypesFailClosedInsteadOfOpeningFeatures() {
        val snapshot = buildLauncherSnapshot(1, 1000) { key -> when (key) {
            // 字符串冒充布尔：必须当作关，而不是当作真。
            LauncherSnapshotWire.MASTER -> "true"
            LauncherSnapshotWire.COUNT -> 1
            else -> null
        } }!!
        assertEquals(0, snapshot.features)
        assertFalse(snapshot.armed)
        // 缺省上限回落到区间下界，而不是 0 或任意值。
        assertEquals(LauncherSnapshotWire.COUNT_LIMIT_DEFAULT, snapshot.countLimit)
    }

    @Test fun countLimitDefaultComesFromTheFeatureCatalog() {
        assertEquals(20, LauncherSnapshotWire.COUNT_LIMIT_DEFAULT)
        val snapshot = buildLauncherSnapshot(1, 1000) { key ->
            if (key == LauncherSnapshotWire.MASTER) true else null
        }!!
        assertEquals(LauncherSnapshotWire.COUNT_LIMIT_DEFAULT, snapshot.countLimit)
    }

    @Test fun missingOptionalMenuEntriesStayOffAndUnknownOptionsStayOff() {
        val settings = mapOf(
            LauncherSnapshotWire.MASTER to true,
            LauncherSnapshotWire.COUNT to false,
        )
        val snapshot = buildLauncherSnapshot(1, 1000) { key ->
            settings[key]
        }!!
        // 数量选项默认开启，这里显式关闭；其余未写入的可选菜单项必须保持关闭。
        assertEquals(0, snapshot.features)
        assertTrue(snapshot.armed)
    }

    @Test fun countLimitIsAcceptedFromSliderStringAndClamped() {
        val base = mapOf(LauncherSnapshotWire.MASTER to true, LauncherSnapshotWire.COUNT to true)
        for ((stored, expected) in listOf(
            "9" to 9, 8 to 8, 4 to LauncherSnapshotWire.COUNT_LIMIT_MIN,
            21 to LauncherSnapshotWire.COUNT_LIMIT_MAX, "bad" to LauncherSnapshotWire.COUNT_LIMIT_DEFAULT,
        )) {
            val snapshot = buildLauncherSnapshot(1, 1000) { key ->
                if (key == LauncherSnapshotWire.COUNT_LIMIT) stored else base[key]
            }!!
            assertEquals("stored=$stored", expected, snapshot.countLimit)
        }
    }

    @Test fun unusableClockOrRevisionRefuseToAnswer() {
        assertNull(buildLauncherSnapshot(0, 1000) { null })
        assertNull(buildLauncherSnapshot(-1, 1000) { null })
        assertNull(buildLauncherSnapshot(1, 0) { null })
        assertNull(buildLauncherSnapshot(1, -1) { null })
    }

    @Test fun recoveryPreferencesAreNeitherReadNorForwarded() {
        val configurationKeys = setOf(
            LauncherSnapshotWire.MASTER, LauncherSnapshotWire.COUNT, LauncherSnapshotWire.SHARE,
            LauncherSnapshotWire.FREEFORM, LauncherSnapshotWire.TASKS, LauncherSnapshotWire.COUNT_LIMIT,
            SafeModeKeys.disabled("launcher"),
        )
        val resetKeys = listOf(
            SafeModeKeys.reset("system_server"), SafeModeKeys.reset("systemui"),
            SafeModeKeys.reset(LAUNCHER_PACKAGE), FRAMEWORK_FUSE_RESET_KEY,
            SafeModeKeys.reset("launcher"),
        )
        val values = listOf<Any?>(
            null, -1L, 0L, 1L, Int.MAX_VALUE, Int.MAX_VALUE.toLong(),
            Int.MAX_VALUE.toLong() + 1, 1_700_000_000_000L, Long.MAX_VALUE, "1", true,
        )
        for (resetKey in resetKeys) for (value in values) {
            val settings = mapOf(
                LauncherSnapshotWire.MASTER to true,
                LauncherSnapshotWire.COUNT to false,
                resetKey to value,
            )
            val reads = mutableSetOf<String>()
            val snapshot = buildLauncherSnapshot(1, 1000) { key ->
                reads += key
                settings[key]
            }!!
            assertEquals(configurationKeys, reads)
            assertTrue(snapshot.armed)
            // 未写入的可选菜单项不得因为主开关打开而自动出现。
            assertEquals(0, snapshot.features)
            assertEquals(0, snapshot.resetToken)
            assertEquals(0, snapshot.toWire()[8])
        }
    }

    @Test fun manualSafetyModeMasksFeaturesEvenWhenMasterIsEnabled() {
        val settings = mapOf(LauncherSnapshotWire.MASTER to true,
            SafeModeKeys.disabled("launcher") to true)
        val snapshot = buildLauncherSnapshot(1, 1000) { settings[it] }!!
        assertEquals(0, snapshot.features)
        assertFalse(snapshot.armed)
    }

    @Test fun explicitRecoveryCanProceedWithNoFeaturesWithoutEnablingThem() {
        val snapshot = buildLauncherSnapshot(1, 1000, resetToken = 77) { null }!!
        assertEquals(0, snapshot.features)
        assertTrue(snapshot.armed)
        assertEquals(77, snapshot.resetToken)
    }

    @Test fun noFeatureCombinationAuthorizesRecovery() {
        val featureKeys = listOf(
            LauncherSnapshotWire.COUNT, LauncherSnapshotWire.SHARE,
            LauncherSnapshotWire.FREEFORM, LauncherSnapshotWire.TASKS,
        )
        for (master in listOf(false, true)) for (mask in 0..LauncherSnapshotWire.FEATURE_MASK) {
            val settings = mutableMapOf<String, Any>(LauncherSnapshotWire.MASTER to master)
            for ((index, key) in featureKeys.withIndex()) {
                settings[key] = (mask and (1 shl index)) != 0
            }
            val snapshot = buildLauncherSnapshot(1, 1000) { settings[it] }!!
            val words = snapshot.toWire()
            assertEquals(master, snapshot.armed)
            assertEquals(if (master) mask else 0, snapshot.features)
            assertEquals(0, snapshot.resetToken)
            assertEquals(LauncherSnapshotWire.WORDS, words.size)
            assertEquals(0, words[8])
            assertEquals(0, words[9])
        }
    }

    @Test fun configurationRevisionsAndToggleCyclesNeverMintResetTokens() {
        for (revision in listOf(1, 2, Int.MAX_VALUE)) {
            for (enabled in listOf(false, true, false, true)) {
                val snapshot = buildLauncherSnapshot(revision, 1000) { key ->
                    when (key) {
                        LauncherSnapshotWire.MASTER, LauncherSnapshotWire.COUNT -> enabled
                        else -> null
                    }
                }!!
                assertEquals(revision, snapshot.revision)
                assertEquals(enabled, snapshot.armed)
                assertEquals(0, snapshot.resetToken)
                assertEquals(0, snapshot.toWire()[8])
            }
        }
    }

    @Test fun wireEncodingKeepsFieldOrderAndASecondWideLease() {
        val snapshot = LauncherSnapshot(revision = 7, issuedSeconds = 3000000,
            features = LauncherSnapshotWire.FEATURE_COUNT, countLimit = 6, armed = true, resetToken = 3)
        val words = snapshot.toWire()
        assertEquals(LauncherSnapshotWire.WORDS, words.size)
        assertEquals(LauncherSnapshotWire.ACK, words[0])
        assertEquals(LauncherSnapshotWire.PROTOCOL, words[1])
        assertEquals(7, words[2])
        assertEquals(3000000, words[3])
        // 到期 = 签发 + 30 秒；用秒编码，长时间开机（300 万秒 ≈ 34.7 天）也不溢出。
        assertEquals(3000030, words[4])
        assertEquals(LauncherSnapshotWire.FEATURE_COUNT, words[5])
        assertEquals(6, words[6])
        assertEquals(1, words[7])
        assertEquals(3, words[8])
        assertEquals(0, words[9])
    }

    @Test fun wireExpirySaturatesInsteadOfGoingNegative() {
        // 即便有人把签发时间喂到接近上界，到期也必须落在合法范围内，
        // 绝不允许回绕成负数（原生侧会整包判畸形）。
        val words = LauncherSnapshot(1, Int.MAX_VALUE, 0, 5, true, 0).toWire()
        assertTrue(words[4] >= 0)
        assertTrue(words[4] >= words[3])
    }
}
