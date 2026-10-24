/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖安装放宽范围的约束测试。
 *
 * 这里的每一条断言都对应一条「放宽过头会变成提权」的路径，改动白名单时必须先想清楚
 * 是否愿意承担那条路径。
 */
class CorePatchSignaturePolicyTest {
    private val installFrames = setOf("verifySignatures", "preparePackage")

    /** 生产 hook 的完整判定顺序：先筛能力白名单，再筛安装链路。 */
    private fun forges(
        capability: Int?,
        frames: Set<String> = installFrames,
        override: Boolean = true,
        sharedUser: Boolean = false,
    ): Boolean = CorePatchSignaturePolicy.forgesCapability(
        capability,
        overrideEnabled = override,
        sharedUserEnabled = sharedUser,
    ) && CorePatchSignaturePolicy.inInstallPath(frames)

    @Test
    fun overrideForgesInstalledDataAndRollback() {
        assertTrue(forges(CorePatchSignaturePolicy.CAP_INSTALLED_DATA))
        assertTrue(forges(CorePatchSignaturePolicy.CAP_ROLLBACK))
    }

    /**
     * key-set 与「沿用已安装签名」只认用户安装链，开机扫描一律不放开。
     *
     * 用户安装有两个可辨识的帧：`preparePackage`（权限签名延续、以及它自己内联的 key-set 判定）
     * 与 `installPackagesTraced`/`reconcileInstallPackages`（session 提交后的批量 reconcile）。
     * 开机扫描走 `processParseResult -> addForInitLI -> reconcilePackages`，这三个帧一个都没有，
     * 因此必须判否（失败关闭）—— 否则会在无人操作时改写系统包的签名与 key-set 判定。
     */
    @Test
    fun userInstallPathAcceptsInstallPipelineFramesOnly() {
        assertTrue(CorePatchSignaturePolicy.inUserInstallPath(setOf("preparePackage")))
        // javac 合成与 lambda 后缀同样算用户安装。
        assertTrue(CorePatchSignaturePolicy.inUserInstallPath(setOf("lambda\$preparePackage\$3")))
        assertTrue(CorePatchSignaturePolicy.inUserInstallPath(setOf("access\$preparePackage")))
        assertTrue(CorePatchSignaturePolicy.inUserInstallPath(setOf("installPackagesTraced")))
        assertTrue(CorePatchSignaturePolicy.inUserInstallPath(setOf("reconcileInstallPackages")))
        // 开机扫描：这些帧单独出现时不得放开。
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(setOf("verifySignatures")))
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(setOf("reconcilePackages")))
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(setOf("addForInitLI")))
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(setOf("processParseResult")))
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(emptySet()))
        // 前缀相似但不带 `$` 分隔的帧名不能误判成安装链。
        assertFalse(CorePatchSignaturePolicy.inUserInstallPath(setOf("preparePackageFoo")))
    }

    /** 包名门禁是「沿用已安装签名」唯一的冒充防线，空值与不同名都必须判否。 */
    @Test
    fun preSignatureRequiresVerbatimPackageNameMatch() {
        assertTrue(CorePatchSignaturePolicy.samePackageName("com.example.app", "com.example.app"))
        assertFalse(CorePatchSignaturePolicy.samePackageName("com.example.app", "com.example.other"))
        assertFalse(CorePatchSignaturePolicy.samePackageName("com.example.app", "com.example.appp"))
        for (blank in listOf(null, "", "   ")) {
            assertFalse(CorePatchSignaturePolicy.samePackageName(blank, "com.example.app"))
            assertFalse(CorePatchSignaturePolicy.samePackageName("com.example.app", blank))
        }
    }
    @Test
    fun permissionAndAuthAreNeverForged() {
        for (capability in listOf(
            CorePatchSignaturePolicy.CAP_PERMISSION,
            CorePatchSignaturePolicy.CAP_AUTH,
        )) {
            assertFalse("$capability", forges(capability, sharedUser = true))
        }
    }

    @Test
    fun sharedUserNeedsItsOwnSwitch() {
        assertFalse(forges(CorePatchSignaturePolicy.CAP_SHARED_USER_ID))
        assertTrue(forges(
            CorePatchSignaturePolicy.CAP_SHARED_USER_ID, override = false, sharedUser = true))
    }

    @Test
    fun bothSwitchesOffForgesNothing() {
        for (capability in listOf(
            CorePatchSignaturePolicy.CAP_INSTALLED_DATA,
            CorePatchSignaturePolicy.CAP_SHARED_USER_ID,
            CorePatchSignaturePolicy.CAP_ROLLBACK,
        )) {
            assertFalse("$capability", forges(capability, override = false, sharedUser = false))
        }
    }

    @Test
    fun outsideInstallPathForgesNothingEvenWithBothSwitches() {
        // 权限校验等非安装链路同样会问 checkCapability，此时必须走宿主原始判定。
        val outside = setOf("checkSignatures", "getPackagesForUid", "onResume")
        assertFalse(forges(
            CorePatchSignaturePolicy.CAP_INSTALLED_DATA, frames = outside, sharedUser = true))
    }

    @Test
    fun onlyPackageManagementInstallFramesCount() {
        val inside = setOf(
            "verifySignatures", "matchSignatureInSystem", "canJoinSharedUserId", "preparePackage",
        )
        inside.forEach { assertTrue(it, CorePatchSignaturePolicy.inInstallPath(setOf(it))) }

        // `$` 在 Kotlin 字符串里是模板起始，必须转义，否则这些宿主真实存在的帧名会被解析成变量引用。
        val synthetic = setOf(
            "verifySignatures\$1", "lambda\$verifySignatures\$0", "access\$canJoinSharedUserId",
        )
        synthetic.forEach { assertTrue(it, CorePatchSignaturePolicy.inInstallPath(setOf(it))) }

        val outside = setOf(
            "checkSignatures", "getSignatures", "hasCommonAncestor", "equals",
            "onActivityResult", "checkPermission", "bindService",
        )
        outside.forEach { assertFalse(it, CorePatchSignaturePolicy.inInstallPath(setOf(it))) }
    }

    @Test
    fun frameNamesReadRealStackFrames() {
        val names = CorePatchSignaturePolicy.frameNames(Thread.currentThread().stackTrace)
        assertTrue(names.contains("frameNamesReadRealStackFrames"))
    }
}
