/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess

/** 包管理服务的签名、降级、系统应用更新与隔离校验修补。 */
internal object SystemFrameworkCorePatchHooks {
    private const val PM_SERVICE_IMPL = "com.android.server.pm.PackageManagerServiceImpl"
    private const val INSTALL_HELPER = "com.android.server.pm.InstallPackageHelper"
    private const val PM_UTILS = "com.android.server.pm.PackageManagerServiceUtils"
    private const val SIGNING_DETAILS = "android.content.pm.SigningDetails"

    /** 安装请求存在嵌套时，只有内层 preparePackage 执行期间才放宽持久化判定。 */
    private val prepareDepth = ThreadLocal.withInitial<Int> { 0 }

    /** Cts 判定只在应用包安装器选择链路中短暂伪造，不污染其它包管理调用。 */
    private val ctsDepth = ThreadLocal.withInitial<Int> { 0 }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_core_patch_allow_update_system_app")) {
            count += installAllowUpdateSystemApp(loader)
        }
        if (settings.isOn("system_framework_core_patch_bypass_isolation_violation")) {
            count += installBypassIsolationViolation(loader)
        }
        if (settings.isOn("system_framework_core_patch_disable_low_api_check")) {
            count += installDisableLowApiCheck(loader)
        }
        if (settings.isOn("system_framework_core_patch_disable_persistent")) {
            count += installDisablePersistent(loader)
        }
        if (settings.isOn("system_framework_core_patch_downgrade")) count += installDowngrade(loader)
        if (settings.isOn("system_framework_core_patch_exact_signature")) count += installExactSignature(loader)
        if (settings.isOn("system_framework_core_patch_verification_agent")) {
            count += installVerificationAgent(loader)
        }
        if (settings.isOn("system_framework_core_patch_signature")) count += installSignatureBypass(loader)
        if (settings.isOn("system_framework_core_patch_digest")) count += installDigestBypass(loader)
        if (settings.isOn("system_framework_core_patch_shared_user")) count += installSharedUserBypass(loader)
        if (settings.isOn("system_framework_use_android_package_installer")) {
            count += installAndroidPackageInstaller(loader)
        }
        if (settings.isOn("system_framework_core_patch_fingerprint_guard")) {
            count += installFingerprintGuard(loader)
        }
        return count
    }

    private fun installAllowUpdateSystemApp(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        val method = Reflect.findMethods(clazz, "canBeUpdate", paramCount = 1).firstOrNull {
            it.parameterTypes[0] == String::class.java && it.returnType == Void.TYPE
        } ?: return missing("$PM_SERVICE_IMPL#canBeUpdate(String)")
        return if (HookRuntime.hook(method, "core_patch_allow_update/$PM_SERVICE_IMPL#canBeUpdate") { null }) 1 else 0
    }

    private fun installBypassIsolationViolation(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        val method = Reflect.findMethods(clazz, "verifyIsolationViolation", paramCount = 2).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("$PM_SERVICE_IMPL#verifyIsolationViolation(ParsedPackage,InstallSource)")
        return if (HookRuntime.hook(method, "core_patch_isolation/$PM_SERVICE_IMPL#verifyIsolationViolation") {
                null
            }
        ) 1 else 0
    }

    private fun installDisableLowApiCheck(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, INSTALL_HELPER) ?: return missing(INSTALL_HELPER)
        val method = Reflect.findMethods(clazz, "preparePackage", paramCount = 1).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("$INSTALL_HELPER#preparePackage(InstallRequest)")
        return if (HookRuntime.hook(method, "core_patch_low_api/$INSTALL_HELPER#preparePackage") { chain ->
                val request = chain.args.getOrNull(0)
                val installArgs = request?.let { Reflect.readField(it, "mInstallArgs") }
                if (installArgs != null) {
                    val flags = Reflect.readField(installArgs, "mInstallFlags") as? Int
                    if (flags != null) {
                        Reflect.writeField(installArgs, "mInstallFlags", flags or INSTALL_FLAG_LOW_TARGET_SDK_BYPASS)
                    } else {
                        ModuleLog.warn("core_patch_low_api: mInstallFlags unavailable")
                    }
                }
                chain.proceed()
            }
        ) 1 else 0
    }

    private fun installDisablePersistent(loader: ClassLoader): Int {
        val helper = Reflect.loadClass(loader, INSTALL_HELPER)
        val prepare = helper?.let { Reflect.findMethods(it, "preparePackage", paramCount = 1) }
            ?.firstOrNull { it.returnType == Void.TYPE }
        val setting = Reflect.loadClass(loader, "com.android.server.pm.PackageSetting")
        val isPersistent = setting?.let { Reflect.findMethods(it, "isPersistent", paramCount = 0) }
            ?.firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (prepare == null || isPersistent == null) {
            ModuleLog.warn("core_patch_persistent: PackageSetting#isPersistent or preparePackage not found")
            return 0
        }
        var count = 0
        if (HookRuntime.hook(prepare, "core_patch_persistent/$INSTALL_HELPER#preparePackage") { chain ->
                prepareDepth.set(prepareDepth.currentDepth() + 1)
                try {
                    chain.proceed()
                } finally {
                    val next = prepareDepth.currentDepth() - 1
                    if (next <= 0) prepareDepth.remove() else prepareDepth.set(next)
                }
            }
        ) count++
        if (HookRuntime.hook(isPersistent, "core_patch_persistent/PackageSetting#isPersistent") { chain ->
                val original = chain.proceed()
                if (original == true && prepareDepth.currentDepth() > 0) false else original
            }
        ) count++
        return count
    }

    private fun installDowngrade(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, PM_UTILS) ?: return missing(PM_UTILS)
        val methods = Reflect.findMethods(clazz, "checkDowngrade")
            .filter { it.returnType == Void.TYPE }
        if (methods.isEmpty()) return missing("$PM_UTILS#checkDowngrade")
        return methods.count {
            HookRuntime.hook(it, "core_patch_downgrade/$PM_UTILS#checkDowngrade") { null }
        }
    }

    private fun installExactSignature(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, SIGNING_DETAILS) ?: return missing(SIGNING_DETAILS)
        val methods = Reflect.findMethods(clazz, "signaturesMatchExactly")
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
        if (methods.isEmpty()) return missing("$SIGNING_DETAILS#signaturesMatchExactly")
        return methods.count {
            HookRuntime.hookReturning(
                it,
                "core_patch_exact_signature/$SIGNING_DETAILS#signaturesMatchExactly",
                true,
            )
        }
    }

    private fun installVerificationAgent(loader: ClassLoader): Int {
        var count = 0
        for (className in listOf(
            "com.android.server.pm.VerifyingSession",
            "com.android.server.pm.VerificationParams",
            PM_SERVICE_IMPL,
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (method in Reflect.findMethods(clazz, "isVerificationEnabled")) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hookReturning(
                        method,
                        "core_patch_verification_agent/$className#isVerificationEnabled",
                        false,
                    )
                ) count++
            }
        }
        return count
    }

    private fun installSignatureBypass(loader: ClassLoader): Int {
        var count = 0
        val scan = Reflect.loadClass(loader, "com.android.server.pm.ScanPackageUtils")
        if (scan != null) {
            for (method in Reflect.findMethods(scan, "assertMinSignatureSchemeIsValid")) {
                if (method.returnType != Void.TYPE) continue
                if (HookRuntime.hook(
                        method,
                        "core_patch_signature/ScanPackageUtils#assertMinSignatureSchemeIsValid",
                    ) { null }
                ) count++
            }
        }
        val verifier = Reflect.loadClass(loader, "android.util.apk.ApkSignatureVerifier")
        if (verifier != null) {
            for (method in Reflect.findMethods(
                verifier,
                "getMinimumSignatureSchemeVersionForTargetSdk",
                paramCount = 1,
            )) {
                if (method.returnType != Int::class.javaPrimitiveType) continue
                if (HookRuntime.hookReturning(
                        method,
                        "core_patch_signature/ApkSignatureVerifier#getMinimumSignatureSchemeVersionForTargetSdk",
                        1,
                    )
                ) count++
            }
        }
        val strictJar = Reflect.loadClass(loader, "android.util.jar.StrictJarVerifier")
        if (strictJar != null) {
            val field = Reflect.findField(strictJar, "signatureSchemeRollbackProtectionsEnforced")
            if (field != null && UnsafeAccess.isAvailable) {
                for (constructor in strictJar.declaredConstructors) {
                    if (HookRuntime.hook(
                            constructor,
                            "core_patch_signature/StrictJarVerifier#<init>",
                        ) { chain ->
                            val result = chain.proceed()
                            UnsafeAccess.putBooleanField(field, chain.thisObject, false)
                            result
                        }
                    ) count++
                }
            }
        }
        return count
    }

    private fun installDigestBypass(loader: ClassLoader): Int {
        var count = 0
        val strictJar = Reflect.loadClass(loader, "android.util.jar.StrictJarVerifier")
        if (strictJar != null) {
            for (name in listOf("verifyMessageDigest", "verify")) {
                for (method in Reflect.findMethods(strictJar, name)) {
                    if (method.returnType != Boolean::class.javaPrimitiveType) continue
                    if (HookRuntime.hookReturning(
                            method,
                            "core_patch_digest/StrictJarVerifier#$name",
                            true,
                        )
                    ) count++
                }
            }
        }
        val digest = Reflect.loadClass(loader, "java.security.MessageDigest")
        if (digest != null) {
            for (method in Reflect.findMethods(digest, "isEqual", paramCount = 2)) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hookReturning(method, "core_patch_digest/MessageDigest#isEqual", true)) count++
            }
        }
        val assets = Reflect.loadClass(loader, "android.content.res.AssetManager")
        val table = assets?.let { Reflect.findMethods(it, "containsAllocatedTable", paramCount = 0) }
            ?.firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (table != null &&
            HookRuntime.hookReturning(table, "core_patch_digest/AssetManager#containsAllocatedTable", false)
        ) count++
        return count
    }

    private fun installSharedUserBypass(loader: ClassLoader): Int {
        var count = 0
        val reconcile = Reflect.loadClass(loader, "com.android.server.pm.ReconcilePackageUtils")
        val allowField = reconcile?.let {
            Reflect.staticFieldOrNull(it, "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS")
        }
        if (allowField != null && UnsafeAccess.putStaticBoolean(allowField, true)) count++

        val signing = Reflect.loadClass(loader, SIGNING_DETAILS)
        if (signing != null) {
            for (method in Reflect.findMethods(signing, "hasCommonAncestor", paramCount = 1)) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "core_patch_shared_user/SigningDetails#hasCommonAncestor") { chain ->
                        val fromVerify = Thread.currentThread().stackTrace.any {
                            it.methodName == "verifySignatures"
                        }
                        if (fromVerify) true else chain.proceed()
                    }
                ) count++
            }
        }
        val utils = Reflect.loadClass(loader, PM_UTILS)
        if (utils != null) {
            for (method in Reflect.findMethods(utils, "verifySignatures")) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "core_patch_shared_user/PackageManagerServiceUtils#verifySignatures") {
                        chain ->
                        val fromPrepare = Thread.currentThread().stackTrace.any {
                            it.methodName.startsWith("preparePackage")
                        }
                        if (fromPrepare) false else chain.proceed()
                    }
                ) count++
            }
        }
        return count
    }

    private fun installAndroidPackageInstaller(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        var count = 0
        for (method in Reflect.findMethods(clazz, "isCTS", paramCount = 0)) {
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (HookRuntime.hook(method, "android_package_installer/$PM_SERVICE_IMPL#isCTS") {
                    ctsDepth.currentDepth() > 0
                }
            ) count++
        }
        for (name in listOf(
            "hookChooseBestActivity",
            "updateDefaultPkgInstallerLocked",
            "assertValidApkAndInstaller",
        )) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (HookRuntime.hook(method, "android_package_installer/$PM_SERVICE_IMPL#$name") { chain ->
                        ctsDepth.set(ctsDepth.currentDepth() + 1)
                        try {
                            chain.proceed()
                        } finally {
                            val next = ctsDepth.currentDepth() - 1
                            if (next <= 0) ctsDepth.remove() else ctsDepth.set(next)
                        }
                    }
                ) count++
            }
        }
        return count
    }

    private fun installFingerprintGuard(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintProvider",
        ) ?: return missing("FingerprintProvider")
        val method = Reflect.findMethods(clazz, "copyFingerprintXml", paramCount = 3)
            .firstOrNull { it.returnType == Void.TYPE }
            ?: return missing("FingerprintProvider#copyFingerprintXml(Context,int,int)")
        return if (HookRuntime.hook(
                method,
                "core_patch_fingerprint/FingerprintProvider#copyFingerprintXml",
            ) { null }
        ) 1 else 0
    }

    private fun missing(target: String): Int {
        ModuleLog.warn("core_patch: $target not found")
        return 0
    }

    /** 上游通过该安装标志绕过低 targetSdk 校验，常量值保持逐字一致。 */
    private const val INSTALL_FLAG_LOW_TARGET_SDK_BYPASS = 0x01000000

    private fun ThreadLocal<Int>.currentDepth(): Int = get() ?: 0
}
