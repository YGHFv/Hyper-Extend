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
