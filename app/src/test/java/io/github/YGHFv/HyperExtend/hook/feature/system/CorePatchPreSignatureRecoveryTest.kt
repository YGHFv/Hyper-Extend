/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.hook.HookInvocation
import org.junit.Assert.*
import org.junit.Test

/** 以可反射的宿主替身验证返回包装、错误状态与单次原生调用，不执行设备安装。 */
class CorePatchPreSignatureRecoveryTest {
    class Certificate(val value: String)

    class Details(val current: Array<Certificate>?, val history: Array<Certificate>, val scheme: Int) {
        constructor(old: Details) : this(old.current?.clone(), old.history.clone(), old.scheme)
        fun getSignatures(): Array<Certificate>? = current
    }

    class OldDetails {
        @JvmField val signatures: Array<Certificate>?
        constructor(signatures: Array<Certificate>?) { this.signatures = signatures }
        constructor(old: OldDetails) { signatures = old.signatures?.clone() }
    }

    class Rejected(@JvmField val error: Int) : RuntimeException("原生拒绝")

    interface Result {
        fun isError(): Boolean
        fun getErrorCode(): Int
        fun getErrorMessage(): String?
        fun getException(): Exception?
    }

    interface Input {
        fun success(value: Any?): Result
        fun error(code: Int, message: String?, exception: Exception?): Result
    }

    class Parse : Input, Result {
        var code = -103
        var message: String? = "无法收集证书"
        @JvmField var exception: Exception? = IllegalStateException("证书缺失")
        var value: Any? = null
        var failSuccess = false
        var resets = 0
        override fun isError() = code != 1
        override fun getErrorCode() = code
        override fun getErrorMessage() = message
        override fun getException() = exception
        fun reset(): Input {
            resets++
            code = 1
            message = null
            exception = null
            value = null
            return this
        }
        override fun success(value: Any?): Result {
            if (failSuccess) error("返回包装失败")
            check(!isError()) { "不能在错误状态直接返回成功" }
            this.value = value
            return this
        }
        override fun error(code: Int, message: String?, exception: Exception?): Result {
            this.code = code
            this.message = message
            this.exception = exception
            return this
        }
    }

    class Wrapped(val details: Details, val digests: Map<*, *>?)

    class Verifier {
        companion object {
            @JvmStatic fun verifyV1Signature(input: Input, path: String, full: Boolean): Result = Parse()
            @JvmStatic fun verifyV3AndBelowSignatures(input: Input, path: String, version: Int, full: Boolean): Result = Parse()
            @JvmStatic fun verifySignaturesInternal(input: Input, path: String, version: Int, full: Boolean): Result = Parse()
            @JvmStatic fun verifySignatures(input: Input, path: String, version: Int, full: Boolean): Result = Parse()
        }
    }

    class OldVerifier {
        companion object {
            @JvmStatic fun verifyV1Signature(path: String, full: Boolean): OldDetails = OldDetails(null)
            @JvmStatic fun verify(path: String, version: Int): OldDetails = OldDetails(null)
            @JvmStatic fun unsafeGetCertsWithoutVerification(path: String, version: Int): OldDetails = OldDetails(null)
        }
    }

    class WrongVerifier {
        companion object {
            @JvmStatic fun verifyV1Signature(input: Input, path: String, full: Boolean): Any = Parse()
        }
    }

    private fun targets(verifier: Class<*> = Verifier::class.java) =
        CorePatchPreSignatureTargets.resolve(verifier, Details::class.java, Certificate::class.java,
            Rejected::class.java, Input::class.java, Result::class.java, Parse::class.java, Wrapped::class.java)

    private fun oldTargets() = CorePatchPreSignatureTargets.resolve(OldVerifier::class.java,
        OldDetails::class.java, Certificate::class.java, Rejected::class.java)

    private fun details(multiple: Boolean = false) = Details(
        if (multiple) arrayOf(Certificate("甲"), Certificate("乙")) else arrayOf(Certificate("当前")),
        arrayOf(Certificate("祖先")), 3)

    private fun installed(details: Any? = details()) =
        CorePatchPreSignatureRecovery.Installed("com.example.app", details)

    private fun recover(parse: Parse, old: CorePatchPreSignatureRecovery.Installed? = installed(),
        enabled: Boolean = true, path: Any? = "/data/app/staged/base.apk"): Any? =
        CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, path, true),
            { enabled }, { parse }, { old })

    @Test fun exactTargetsResolveModernWrapperAndThreeCallers() {
        val targets = targets()
        assertEquals(listOf(Input::class.java, String::class.java, Boolean::class.javaPrimitiveType),
            targets.verify.parameterTypes.toList())
        assertEquals(3, targets.callers.size)
        assertNotNull(targets.parse)
    }

    @Test fun wrongReturnTypeIsRejectedInsteadOfMatchingByParameterCount() {
        assertTrue(runCatching { targets(WrongVerifier::class.java) }.isFailure)
    }

    @Test fun oldDirectReturnAndSignatureFieldAreSupported() {
        val targets = oldTargets()
        assertNull(targets.parse)
        assertTrue(targets.hasSignatures(OldDetails(arrayOf(Certificate("旧")))))
        assertFalse(targets.hasSignatures(OldDetails(null)))
    }

    @Test fun certificateRecoveryReturnsCorrectWrapperAndKeepsRotationHistory() {
        val old = details()
        val parse = Parse()
        assertSame(parse, recover(parse, installed(old)))
        assertFalse(parse.isError())
        val wrapped = parse.value as Wrapped
        assertNotSame(old, wrapped.details)
        assertNotSame(old.current, wrapped.details.current)
        assertEquals("当前", wrapped.details.current!!.single().value)
        assertEquals("祖先", wrapped.details.history.single().value)
        assertEquals(3, wrapped.details.scheme)
        assertNull(wrapped.digests)
        assertEquals(1, parse.resets)
        assertEquals("当前", old.current!!.single().value)
    }

    @Test fun multipleCurrentSignersRemainMultipleAndHistoryDoesNotBecomeCurrent() {
        val parse = Parse()
        recover(parse, installed(details(multiple = true)))
        val copy = (parse.value as Wrapped).details
        assertEquals(listOf("甲", "乙"), copy.current!!.map { it.value })
        assertEquals(listOf("祖先"), copy.history.map { it.value })
    }

    @Test fun successfulVerificationIsNeverReplacedOrQueried() {
        val parse = Parse().apply { code = 1; value = "原生成功" }
        var reads = 0
        val result = CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { true }, { parse }, { reads++; installed() })
        assertSame(parse, result)
        assertEquals("原生成功", parse.value)
        assertEquals(0, reads)
    }

    @Test fun disabledOptionPreservesFailureAndDoesNotReadPackageManager() {
        val parse = Parse()
        var reads = 0
        assertSame(parse, CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { false }, { parse }, { reads++; installed() }))
        assertEquals(-103, parse.code)
        assertEquals(0, reads)
    }

    @Test fun onlyNoCertificatesErrorIsRecovered() {
        for (code in listOf(-7, -102, -104, -105, -106, -112)) {
            val parse = Parse().apply { this.code = code }
            assertSame(parse, recover(parse))
            assertEquals(code, parse.code)
            assertEquals(0, parse.resets)
        }
    }

    @Test fun newInstallMissingDetailsAndUnknownSignaturesNeverUseSyntheticCertificate() {
        for (old in listOf(null, installed(null), installed(Details(null, emptyArray(), 1)),
                installed(Details(emptyArray(), emptyArray(), 1)), installed("错误结构"),
                CorePatchPreSignatureRecovery.Installed("", details()))) {
            val parse = Parse()
            assertSame(parse, recover(parse, old))
            assertEquals(-103, parse.code)
            assertEquals(0, parse.resets)
        }
    }

    @Test fun invalidInputAndApexAreNotRecovered() {
        for (path in listOf(null, "", 123, "module.apex", "module.APEX")) {
            val parse = Parse()
            recover(parse, path = path)
            assertEquals(-103, parse.code)
        }
        val parse = Parse()
        assertSame(parse, CorePatchPreSignatureRecovery(targets()).invoke(
            arrayOf<Any?>("不是解析输入", "base.apk", true), { true }, { parse }, { installed() }))
        assertEquals(-103, parse.code)
    }

    @Test fun anotherPackagesSignatureCannotBeUsedForArchive() {
        val parse = Parse()
        recover(parse, CorePatchPreSignatureRecovery.Installed("com.example.other", details(), "com.example.app"))
        assertEquals(-103, parse.code)
        assertEquals(0, parse.resets)
    }

    @Test fun queryFailureKeepsOriginalErrorWithoutReset() {
        val parse = Parse()
        var reported = 0
        assertSame(parse, CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { true }, { parse }, { error("读取旧证书失败") }, { reported++ }))
        assertEquals(-103, parse.code)
        assertEquals(0, parse.resets)
        assertEquals(1, reported)
    }

    @Test fun wrappingFailureRestoresMutableParseErrorAndException() {
        val parse = Parse().apply { failSuccess = true }
        val exception = parse.exception
        assertSame(parse, recover(parse))
        assertEquals(-103, parse.code)
        assertEquals("无法收集证书", parse.message)
        assertSame(exception, parse.exception)
    }

    @Test fun modernThrownCertificateErrorIsWrappedUsingParseInput() {
        val parse = Parse()
        val result = CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { true }, { throw Rejected(-103) }, { installed() })
        assertSame(parse, result)
        assertFalse(parse.isError())
        assertTrue(parse.value is Wrapped)
    }

    @Test fun modernWrappingFailurePreservesThrownExceptionIdentity() {
        val parse = Parse().apply { failSuccess = true }
        val failure = Rejected(-103)
        val caught = runCatching {
            CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
                { true }, { throw failure }, { installed() })
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(-103, parse.code)
        assertSame(failure, parse.exception)
    }

    @Test fun failedSettingsReadAndUnexpectedResultKeepNativeOutcome() {
        val parse = Parse()
        assertSame(parse, CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { error("配置读取失败") }, { parse }, { installed() }))
        assertEquals(-103, parse.code)
        val original = Any()
        assertSame(original, CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true),
            { true }, { original }, { installed() }))
    }

    @Test fun reentrantCertificateQueryDoesNotRecursivelyRecover() {
        val recovery = CorePatchPreSignatureRecovery(targets())
        val parse = Parse()
        var reads = 0
        recovery.invoke(arrayOf(parse, "base.apk", true), { true }, { parse }, {
            reads++
            val nested = Parse()
            assertSame(nested, recovery.invoke(arrayOf(nested, "base.apk", true), { true }, { nested },
                { reads++; installed() }))
            assertEquals(-103, nested.code)
            installed()
        })
        assertEquals(1, reads)
        assertFalse(parse.isError())
    }

    @Test fun queryGuardIsClearedAfterFailureForNextInstall() {
        val recovery = CorePatchPreSignatureRecovery(targets())
        val first = Parse()
        recovery.invoke(arrayOf(first, "base.apk", true), { true }, { first }, { error("读取失败") })
        val next = Parse()
        recovery.invoke(arrayOf(next, "base.apk", true), { true }, { next }, { installed() })
        assertFalse(next.isError())
    }

    @Test fun legacyExceptionAndDirectCauseAreRecoveredWithCompleteCopy() {
        val old = OldDetails(arrayOf(Certificate("旧")))
        for (failure in listOf(Rejected(-103), RuntimeException(Rejected(-103)))) {
            val result = CorePatchPreSignatureRecovery(oldTargets()).invoke(arrayOf<Any?>("base.apk", true),
                { true }, { throw failure }, { installed(old) }) as OldDetails
            assertNotSame(old, result)
            assertEquals("旧", result.signatures!!.single().value)
        }
    }

    @Test fun unrelatedNativeExceptionIdentityIsPreserved() {
        for (failure in listOf(Rejected(-105), IllegalStateException("原生异常"))) {
            val caught = runCatching {
                CorePatchPreSignatureRecovery(oldTargets()).invoke(arrayOf<Any?>("base.apk", true),
                    { true }, { throw failure }, { installed() })
            }.exceptionOrNull()
            assertSame(failure, caught)
        }
    }

    @Test fun nativeFailureAndQueryFailureNeverReplayHostInvocation() {
        val failure = Rejected(-103)
        var calls = 0
        val original = { calls++; throw failure }
        val invocation = HookInvocation()
        val caught = runCatching {
            invocation.protect({
                CorePatchPreSignatureRecovery(oldTargets()).invoke(arrayOf<Any?>("base.apk", true), { true },
                    { invocation.proceed(original) }, { error("查询失败") })
            }, original, {})
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
    }

    @Test fun nativeSuccessRecoveryCallsHostExactlyOnce() {
        val invocation = HookInvocation()
        val parse = Parse()
        var calls = 0
        val original = { calls++; parse }
        val result = invocation.protect({
            CorePatchPreSignatureRecovery(targets()).invoke(arrayOf(parse, "base.apk", true), { true },
                { invocation.proceed(original) }, { installed() })
        }, original, {})
        assertSame(parse, result)
        assertFalse(parse.isError())
        assertEquals(1, calls)
    }

    @Test fun allSwitchesAndSafeModeAreChecked() {
        val stack = arrayOf(StackTraceElement("com.android.server.pm.PackageInstallerSession",
            "validateApkInstallLocked", null, -1))
        fun enabled(master: Boolean = true, pre: Boolean = true, override: Boolean = true,
            signature: Boolean = true, safe: Boolean = false) =
            CorePatchPreSignatureRecovery.enabled(master, pre, override, signature, safe, stack)
        assertTrue(enabled())
        assertFalse(enabled(master = false))
        assertFalse(enabled(pre = false))
        assertFalse(enabled(override = false))
        assertFalse(enabled(signature = false))
        assertFalse(enabled(safe = true))
    }

    @Test fun installSessionAndPreparationAreAllowedButBootAndApexAreNot() {
        fun allowed(owner: String, name: String) = CorePatchPreSignatureRecovery.enabled(
            true, true, true, true, false, arrayOf(StackTraceElement(owner, name, null, -1)))
        assertTrue(allowed("com.android.server.pm.PackageInstallerSession", "validateApkInstallLocked"))
        assertTrue(allowed("com.android.server.pm.InstallPackageHelper", "preparePackage\$1"))
        assertFalse(allowed("com.android.server.pm.PackageInstallerSession", "validateApexInstallLocked"))
        assertFalse(allowed("com.android.server.pm.PackageInstallerSession", "verifyApex"))
        assertFalse(allowed("com.android.server.pm.InstallPackageHelper", "initPackageLI"))
        assertFalse(allowed("com.android.server.pm.InstallPackageHelper", "addForInitLI"))
        assertFalse(allowed("com.android.server.pm.InstallPackageHelper", "preparePackages"))
        assertFalse(allowed("com.example.Counterfeit", "preparePackage"))
    }

    @Test fun hostEvidenceShowsErrorRecoveryNeedsWrapperAndReset() {
        val code = javaClass.getResourceAsStream("/core-patch-pre-signature-host-flow.txt")!!
            .bufferedReader().use { it.readText() }
        assertTrue(code.contains("verifyV1Signature"))
        assertTrue(code.contains("const/16 v11, -103"))
        assertTrue(code.contains("0x0089"))
        assertTrue(code.contains("0x0016"))
        assertTrue(code.contains("SigningDetailsWithDigests;->signingDetails"))
        assertTrue(code.contains("ParseTypeImpl;->reset"))
        assertTrue(code.contains("SigningInfo;->getSigningDetails"))
        assertTrue(code.contains("validateApkInstallLocked"))
    }
}
