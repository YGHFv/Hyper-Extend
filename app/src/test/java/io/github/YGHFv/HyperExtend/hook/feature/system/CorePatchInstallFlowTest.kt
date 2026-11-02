/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.hook.HookInvocation
import org.junit.Assert.*
import org.junit.Test

/** 模拟宿主先校验已有权限声明、再校验更新签名的顺序，不模拟实际设备安装。 */
class CorePatchInstallFlowTest {
    private class Rejected(val code: Int) : RuntimeException()

    private fun prepare(
        override: Boolean,
        legacy: Boolean,
        permissionOwner: String = "com.example.app",
        hasDeclaration: Boolean = true,
        declarationError: Int = -112,
    ) {
        if (hasDeclaration && !CorePatchSignaturePolicy.permissionDeclarationResult(
                original = false, overrideEnabled = override, preSignatureEnabled = legacy,
                definingPackageName = permissionOwner, parsedPackageName = "com.example.app",
                frameNames = setOf("preparePackage"), safeMode = false))
            throw Rejected(declarationError)
        if (!CorePatchSignaturePolicy.forgesCapability(CorePatchSignaturePolicy.CAP_INSTALLED_DATA,
                overrideEnabled = override, sharedUserEnabled = false)) throw Rejected(-7)
    }

    @Test fun overrideAlonePassesAllOwnDeclarationChecksBeforeSignatureVerification() {
        for (code in listOf(-112, -126, -127))
            prepare(override = true, legacy = false, declarationError = code)
        prepare(override = true, legacy = false, hasDeclaration = false)
    }

    @Test fun legacyDeclarationSettingIsNotAReplacementForUpdateSignatureBypass() {
        val failure = runCatching { prepare(override = false, legacy = true) }.exceptionOrNull()
        assertEquals(-7, (failure as Rejected).code)
    }

    @Test fun disabledOverrideRestoresRejectionAndCannotRedeclareAnotherPackagesPermission() {
        for (code in listOf(-112, -126, -127)) {
            assertEquals(code, (runCatching {
                prepare(override = false, legacy = false, declarationError = code)
            }.exceptionOrNull() as Rejected).code)
            assertEquals(code, (runCatching {
                prepare(override = true, legacy = true, permissionOwner = "com.example.other",
                    declarationError = code)
            }.exceptionOrNull() as Rejected).code)
        }
    }

    @Test fun failedPostprocessingCannotReplayNativePermissionChecks() {
        val invocation = HookInvocation()
        var calls = 0
        val original = { calls++; false }
        val result = invocation.protect({
            invocation.proceed(original)
            error("反射读取包名失败")
        }, original, {})
        assertEquals(false, result)
        assertEquals(1, calls)
    }

    @Test fun nativeExceptionIdentityAndSingleInvocationArePreserved() {
        val invocation = HookInvocation()
        val failure = IllegalStateException("原生权限校验失败")
        var calls = 0
        val original = { calls++; throw failure }
        val caught = runCatching {
            invocation.protect({ invocation.proceed(original) }, original, {})
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
    }

    @Test fun hostInstructionsContainThreeDeclarationChecksAndTheirRejectionCodes() {
        val code = javaClass.getResourceAsStream("/core-patch-permission-host-flow.txt")!!
            .bufferedReader().use { it.readText() }
        val prepare = code.substringAfter("Lcom/android/server/pm/InstallPackageHelper;->preparePackage")
        val permissionCall = "invoke Lcom/android/server/pm/InstallPackageHelper;->doesSignatureMatchForPermissions"
        assertEquals(3, prepare.lineSequence().count { it.contains(permissionCall) })
        for ((offset, error) in listOf("0x0741" to -126, "0x0830" to -112, "0x095e" to -127)) {
            assertTrue(prepare.contains("$offset $permissionCall"))
            assertTrue(prepare.indexOf("$offset $permissionCall") < prepare.indexOf("const/16 $error"))
        }
        assertTrue(code.contains("const/4 4"))
        assertTrue(code.contains("invoke Landroid/content/pm/SigningDetails;->checkCapability"))
    }
}
