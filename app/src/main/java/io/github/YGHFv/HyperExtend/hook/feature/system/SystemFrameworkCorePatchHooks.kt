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

    /** 总开关与子项的设置键；子项键必须与 `core/SystemFrameworkFeatures` 里的登记逐字一致。 */
    private const val FEATURE = "system_framework_core_patch"
    private const val OP_ALLOW_UPDATE = "system_framework_core_patch_allow_update_system_app"
    private const val OP_ISOLATION = "system_framework_core_patch_bypass_isolation_violation"
    private const val OP_LOW_API = "system_framework_core_patch_disable_low_api_check"
    private const val OP_PERSISTENT = "system_framework_core_patch_disable_persistent"
    private const val OP_DOWNGRADE = "system_framework_core_patch_downgrade"
    private const val OP_EXACT_SIGNATURE = "system_framework_core_patch_exact_signature"
    private const val OP_VERIFICATION_AGENT = "system_framework_core_patch_verification_agent"
    private const val OP_SIGNATURE = "system_framework_core_patch_signature"
    private const val OP_DIGEST = "system_framework_core_patch_digest"
    private const val OP_SHARED_USER = "system_framework_core_patch_shared_user"
    private const val OP_ANDROID_INSTALLER = "system_framework_use_android_package_installer"
    private const val OP_FINGERPRINT = "system_framework_core_patch_fingerprint_guard"

    /** 安装请求存在嵌套时，只有内层 preparePackage 执行期间才放宽持久化判定。 */
    private val prepareDepth = ThreadLocal.withInitial<Int> { 0 }

    /** Cts 判定只在应用包安装器选择链路中短暂伪造，不污染其它包管理调用。 */
    private val ctsDepth = ThreadLocal.withInitial<Int> { 0 }

    /**
     * 总开关关闭时一个 hook 都不装；打开后把**全部子项**的 hook 都装上，
     * 是否真正干预由每个回调里的 `settings.isOn(子项)` 实时决定。
     *
     * 这样用户只需为「总开关」重启一次 system_server，之后调整任意子项都能立即生效
     * （`HookSettings` 不缓存取值，每次 isOn 都读框架托管的 RemotePreferences）。
     * 少数在安装期写静态字段的扩展项无法回退，仍标注为需重启。
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        var count = 0
        count += installAllowUpdateSystemApp(loader, settings)
        count += installBypassIsolationViolation(loader, settings)
        count += installDisableLowApiCheck(loader, settings)
        count += installDisablePersistent(loader, settings)
        count += installDowngrade(loader, settings)
        count += installExactSignature(loader, settings)
        count += installVerificationAgent(loader, settings)
        count += installSignatureBypass(loader, settings)
        count += installDigestBypass(loader, settings)
        count += installSharedUserBypass(loader, settings)
        count += installAndroidPackageInstaller(loader, settings)
        count += installFingerprintGuard(loader, settings)
        return count
    }

    private fun installAllowUpdateSystemApp(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        val method = Reflect.findMethods(clazz, "canBeUpdate", paramCount = 1).firstOrNull {
            it.parameterTypes[0] == String::class.java && it.returnType == Void.TYPE
        } ?: return missing("$PM_SERVICE_IMPL#canBeUpdate(String)")
        return if (HookRuntime.hook(method, "core_patch_allow_update/$PM_SERVICE_IMPL#canBeUpdate") { chain ->
                if (settings.isOn(OP_ALLOW_UPDATE)) null else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installBypassIsolationViolation(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        val method = Reflect.findMethods(clazz, "verifyIsolationViolation", paramCount = 2).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("$PM_SERVICE_IMPL#verifyIsolationViolation(ParsedPackage,InstallSource)")
        return if (HookRuntime.hook(method, "core_patch_isolation/$PM_SERVICE_IMPL#verifyIsolationViolation") { chain ->
                if (settings.isOn(OP_ISOLATION)) null else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installDisableLowApiCheck(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, INSTALL_HELPER) ?: return missing(INSTALL_HELPER)
        val method = Reflect.findMethods(clazz, "preparePackage", paramCount = 1).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("$INSTALL_HELPER#preparePackage(InstallRequest)")
        return if (HookRuntime.hook(method, "core_patch_low_api/$INSTALL_HELPER#preparePackage") { chain ->
                if (!settings.isOn(OP_LOW_API)) return@hook chain.proceed()
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

    private fun installDisablePersistent(loader: ClassLoader, settings: HookSettings): Int {
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
                if (!settings.isOn(OP_PERSISTENT)) return@hook chain.proceed()
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
                if (settings.isOn(OP_PERSISTENT) && original == true && prepareDepth.currentDepth() > 0) {
                    false
                } else {
                    original
                }
            }
        ) count++
        return count
    }

    private fun installDowngrade(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, PM_UTILS) ?: return missing(PM_UTILS)
        val methods = Reflect.findMethods(clazz, "checkDowngrade")
            .filter { it.returnType == Void.TYPE }
        if (methods.isEmpty()) return missing("$PM_UTILS#checkDowngrade")
        return methods.count {
            HookRuntime.hook(it, "core_patch_downgrade/$PM_UTILS#checkDowngrade") { chain ->
                if (settings.isOn(OP_DOWNGRADE)) null else chain.proceed()
            }
        }
    }

    private fun installExactSignature(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, SIGNING_DETAILS) ?: return missing(SIGNING_DETAILS)
        val methods = Reflect.findMethods(clazz, "signaturesMatchExactly")
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
        if (methods.isEmpty()) return missing("$SIGNING_DETAILS#signaturesMatchExactly")
        return methods.count {
            HookRuntime.hook(
                it,
                "core_patch_exact_signature/$SIGNING_DETAILS#signaturesMatchExactly",
            ) { chain -> if (settings.isOn(OP_EXACT_SIGNATURE)) true else chain.proceed() }
        }
    }

    private fun installVerificationAgent(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        for (className in listOf(
            "com.android.server.pm.VerifyingSession",
            "com.android.server.pm.VerificationParams",
            PM_SERVICE_IMPL,
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (method in Reflect.findMethods(clazz, "isVerificationEnabled")) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hook(
                        method,
                        "core_patch_verification_agent/$className#isVerificationEnabled",
                    ) { chain ->
                        if (settings.isOn(OP_VERIFICATION_AGENT)) false else chain.proceed()
                    }
                ) count++
            }
        }
        return count
    }

    private fun installSignatureBypass(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        val scan = Reflect.loadClass(loader, "com.android.server.pm.ScanPackageUtils")
        if (scan != null) {
            for (method in Reflect.findMethods(scan, "assertMinSignatureSchemeIsValid")) {
                if (method.returnType != Void.TYPE) continue
                if (HookRuntime.hook(
                        method,
                        "core_patch_signature/ScanPackageUtils#assertMinSignatureSchemeIsValid",
                    ) { chain -> if (settings.isOn(OP_SIGNATURE)) null else chain.proceed() }
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
                if (HookRuntime.hook(
                        method,
                        "core_patch_signature/ApkSignatureVerifier#getMinimumSignatureSchemeVersionForTargetSdk",
                    ) { chain -> if (settings.isOn(OP_SIGNATURE)) 1 else chain.proceed() }
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
                            if (settings.isOn(OP_SIGNATURE)) {
                                UnsafeAccess.putBooleanField(field, chain.thisObject, false)
                            }
                            result
                        }
                    ) count++
                }
            }
        }
        return count
    }

    private fun installDigestBypass(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        val strictJar = Reflect.loadClass(loader, "android.util.jar.StrictJarVerifier")
        if (strictJar != null) {
            for (name in listOf("verifyMessageDigest", "verify")) {
                for (method in Reflect.findMethods(strictJar, name)) {
                    if (method.returnType != Boolean::class.javaPrimitiveType) continue
                    if (HookRuntime.hook(
                            method,
                            "core_patch_digest/StrictJarVerifier#$name",
                        ) { chain -> if (settings.isOn(OP_DIGEST)) true else chain.proceed() }
                    ) count++
                }
            }
        }
        val digest = Reflect.loadClass(loader, "java.security.MessageDigest")
        if (digest != null) {
            for (method in Reflect.findMethods(digest, "isEqual", paramCount = 2)) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "core_patch_digest/MessageDigest#isEqual") { chain ->
                        if (settings.isOn(OP_DIGEST)) true else chain.proceed()
                    }
                ) count++
            }
        }
        val assets = Reflect.loadClass(loader, "android.content.res.AssetManager")
        val table = assets?.let { Reflect.findMethods(it, "containsAllocatedTable", paramCount = 0) }
            ?.firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (table != null &&
            HookRuntime.hook(table, "core_patch_digest/AssetManager#containsAllocatedTable") { chain ->
                if (settings.isOn(OP_DIGEST)) false else chain.proceed()
            }
        ) count++
        return count
    }

    private fun installSharedUserBypass(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        val reconcile = Reflect.loadClass(loader, "com.android.server.pm.ReconcilePackageUtils")
        val allowField = reconcile?.let {
            Reflect.staticFieldOrNull(it, "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS")
        }

        val signing = Reflect.loadClass(loader, SIGNING_DETAILS)
        if (signing != null) {
            for (method in Reflect.findMethods(signing, "hasCommonAncestor", paramCount = 1)) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "core_patch_shared_user/SigningDetails#hasCommonAncestor") { chain ->
                        if (!settings.isOn(OP_SHARED_USER)) return@hook chain.proceed()
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
                        if (!settings.isOn(OP_SHARED_USER)) return@hook chain.proceed()
                        // 该静态开关按包重算，只能置真不能安全回退；
                        // 因此关闭本子项后需要重启一次 system_server。
                        if (allowField != null && UnsafeAccess.isAvailable) {
                            UnsafeAccess.putStaticBoolean(allowField, true)
                        }
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

    private fun installAndroidPackageInstaller(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, PM_SERVICE_IMPL) ?: return missing(PM_SERVICE_IMPL)
        var count = 0
        for (method in Reflect.findMethods(clazz, "isCTS", paramCount = 0)) {
            if (method.returnType != Boolean::class.javaPrimitiveType) continue
            if (HookRuntime.hook(method, "android_package_installer/$PM_SERVICE_IMPL#isCTS") {
                    settings.isOn(OP_ANDROID_INSTALLER) && ctsDepth.currentDepth() > 0
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
                        if (!settings.isOn(OP_ANDROID_INSTALLER)) return@hook chain.proceed()
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

    private fun installFingerprintGuard(loader: ClassLoader, settings: HookSettings): Int {
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
            ) { chain -> if (settings.isOn(OP_FINGERPRINT)) null else chain.proceed() }
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
