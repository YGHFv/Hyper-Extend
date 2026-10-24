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
import java.lang.reflect.Method

/** 包管理服务的签名、降级、系统应用更新与隔离校验修补。 */
internal object SystemFrameworkCorePatchHooks {
    private const val PM_SERVICE_IMPL = "com.android.server.pm.PackageManagerServiceImpl"
    private const val INSTALL_HELPER = "com.android.server.pm.InstallPackageHelper"
    private const val PM_UTILS = "com.android.server.pm.PackageManagerServiceUtils"
    private const val SIGNING_DETAILS = "android.content.pm.SigningDetails"
    private const val KEY_SET_MANAGER = "com.android.server.pm.KeySetManagerService"
    private const val SHARED_USER_SETTING = "com.android.server.pm.SharedUserSetting"

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
    private const val OP_OVERRIDE_SIGNATURE = "system_framework_core_patch_override_signature"
    private const val OP_SHARED_USER = "system_framework_core_patch_shared_user"
    private const val OP_ANDROID_INSTALLER = "system_framework_use_android_package_installer"
    private const val OP_FINGERPRINT = "system_framework_core_patch_fingerprint_guard"
    private const val OP_USE_PRE_SIGNATURE = "system_framework_core_patch_use_pre_signature"

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
        count += installSignatureOverride(loader, settings)
        count += installSharedUserBypass(loader, settings)
        count += installPreSignature(loader, settings)
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

    /**
     * 覆盖安装「同包名、不同签名」的应用，以及共享 UID 的能力判定。
     *
     * 宿主在 `PackageManagerServiceUtils#verifySignatures` 里用
     * `SigningDetails#checkCapability(SigningDetails, int)` 判定新包能否继承已安装包的能力，
     * 判否就抛 `PackageManagerException(-7, "Existing package … signatures do not match …")`。
     * 上游 DigestCreakPatch 放宽的正是这一处，而本模块此前完全没有挂它 —— 于是其余靶点
     * （签名方案版本、jar 摘要、分包精确匹配）全都被这个异常挡在前面，用户看到的现象就是
     * 「核心破解开了，覆盖装不同签名的包仍然失败」。
     *
     * 放宽范围由 [CorePatchSignaturePolicy] 收敛：`PERMISSION`(4) 与 `AUTH`(16) 绝不伪造；
     * SHARED_USER_ID(2) 只在「共享 UID 签名兼容」开启时才放宽。
     * `checkCapability(String, int)` 这个重载服务于 key-set 升级与账户认证，不挂。
     */
    private fun installSignatureOverride(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, SIGNING_DETAILS) ?: return missing(SIGNING_DETAILS)
        val utils = Reflect.loadClass(loader, PM_UTILS)
        val methods = Reflect.findMethods(clazz, "checkCapability", paramCount = 2)
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
            .filter { it.parameterTypes[0] == clazz }
        if (methods.isEmpty()) return missing("$SIGNING_DETAILS#checkCapability(SigningDetails,int)")
        // UNKNOWN（无签名 / 首次安装）的 getSignatures() 恒为 null，用它区分「真有两把签名」
        // 与「占位对象」。句柄在安装期解析一次：宿主改名时这里直接记一条 missing，
        // 而不是让 hook 装上后在每次回调里静默反射失败（那种「开了没效果」最难查）。
        val getSignatures = Reflect.findMethods(clazz, "getSignatures", paramCount = 0)
            .firstOrNull { it.returnType.isArray }
            ?.also { it.isAccessible = true }
            ?: return missing("$SIGNING_DETAILS#getSignatures()")
        // `SigningDetails` 是 final 类且方法体极小，ART 大概率把 checkCapability 编进调用方
        // 成为内联副本 —— 只挂被 hook 那一个方法会出现「hook 装上了但永远不触发」。
        // 因此先把那几处调用方反优化回解释执行（与上游 SharedUserPatch 同一手法）。
        // 失败不阻断：本机 JIT 未必真的内联过，装了 hook 仍然可能正常生效。
        //
        // 名单就是宿主里 `checkCapability(SigningDetails,int)` 的全部调用方。
        // `comparePackageSignatures` 与 `ComputerEngine#checkSignaturesInternal` 走的是
        // `compareSignatures` 数组比较，**不经过** `checkCapability`，反优化它们没有任何作用；
        // 而它们拦的是 overlay 与特权共享 UID（`-16`），本就不属于「覆盖安装不同签名」这条子项。
        utils?.let { target ->
            for (name in listOf(
                "verifySignatures",
                "matchSignatureInSystem",
                "canJoinSharedUserId",
            )) {
                Reflect.findMethods(target, name).forEach {
                    HookRuntime.deoptimize(it, "core_patch_override_signature/$PM_UTILS#$name")
                }
            }
        }
        return methods.count { method ->
            HookRuntime.hook(
                method,
                "core_patch_override_signature/$SIGNING_DETAILS#checkCapability",
            ) { chain ->
                val override = settings.isOn(OP_OVERRIDE_SIGNATURE)
                val sharedUser = settings.isOn(OP_SHARED_USER)
                if (!override && !sharedUser) return@hook chain.proceed()
                val capability = chain.args.getOrNull(1) as? Int
                // 先用最便宜的判据筛掉「压根不在白名单」的调用：`PERMISSION`(4) 会被权限
                // 校验频繁询问，若每次都抓栈就把一条热点路径变成常数级的栈回溯开销。
                if (!CorePatchSignaturePolicy.forgesCapability(
                        capability,
                        overrideEnabled = override,
                        sharedUserEnabled = sharedUser,
                    )
                ) {
                    return@hook chain.proceed()
                }
                val frames = CorePatchSignaturePolicy.frameNames(Thread.currentThread().stackTrace)
                if (!CorePatchSignaturePolicy.inInstallPath(frames)) return@hook chain.proceed()
                val other = chain.args.getOrNull(0)
                val self = chain.thisObject
                if (self == null || other == null || self === other) return@hook chain.proceed()
                if (getSignatures.invoke(self) == null || getSignatures.invoke(other) == null) {
                    return@hook chain.proceed()
                }
                true
            }
        }
    }

    private fun installSharedUserBypass(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        val reconcile = Reflect.loadClass(loader, "com.android.server.pm.ReconcilePackageUtils")
        val allowField = reconcile?.let {
            Reflect.staticFieldOrNull(it, "ALLOW_NON_PRELOADS_SYSTEM_SHAREDUIDS")
        }
        // 上游 SharedUserPatch 第一件事就是反优化 `reconcilePackages`：`verifySignatures` 与
        // `canJoinSharedUserId` 都是在它内部被调的，调用方一旦把小方法内联进去，
        // 我们挂在被调方上的 hook 就永远不会触发。这里不依赖它成功（本机 JIT 未必内联过），
        // 但必须做，否则「开了共享 UID 兼容却不生效」根本无从判断。
        reconcile?.let { target ->
            Reflect.findMethods(target, "reconcilePackages").forEach {
                HookRuntime.deoptimize(it, "core_patch_shared_user/ReconcilePackageUtils#reconcilePackages")
            }
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
        count += installSharedUserReSign(loader, settings)
        return count
    }

    /**
     * 共享 UID 内成员增删后重选签名，对应上游 SharedUserPatch 的 `addPackage` / `removePackage`。
     *
     * ## 为什么需要
     *
     * 共享 UID 的签名谱系记在 `SharedUserSetting.signatures.mSigningDetails` 上。当 UID 里
     * 最后一把「老签名」的应用被卸载后，记录里留下的仍是那个已经没有任何应用持有的签名谱系；
     * 之后新加的包无论怎么签都会被 `canJoinSharedUserId` / `hasCommonAncestor` 判否。
     * 上游的做法是：在增删的瞬间扫一遍仍在 UID 里的包，若它们已经不含旧签名，
     * 就把 UID 的签名换成「现存包里第一把签名，并与其余包合并谱系」。
     *
     * ## 为什么不自己调「原始方法」
     *
     * `HookRuntime` 不暴露 `callOriginMethod`，这里直接反射调 `checkCapability`，也就是**仍会经过
     * 我们挂在它上面的那个 hook**。安全点在于传的 capability 是 `0`：
     * 它既不在 [CorePatchSignaturePolicy] 的伪造白名单（1/8）里，也不在共享 UID 的那一档（2）里，
     * 所以回调在**抓栈之前**就被 `forgesCapability` 筛掉并走 `chain.proceed()`，
     * 反射拿到的是宿主原始判定 —— 既不会自我强化成「永远相关」，也不会递归。
     * 注意不能指望栈帧挡这里：`addPackage` / `removePackage` 本身就在安装链上，
     * 栈里是有 `preparePackage` 的，真正的防线只有那个 `0`。
     *
     * ## 系统 UID 一律不碰
     *
     * `uidFlags` 带 `FLAG_SYSTEM` 时直接返回（与上游同）。`android`、`system` 这类 UID 一旦
     * 被改写签名记录，影响的是整机框架组件的权限归属，不是「装不上一个应用」这种可恢复故障。
     */
    private fun installSharedUserReSign(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, SHARED_USER_SETTING) ?: return missing(SHARED_USER_SETTING)
        val signingDetails = Reflect.loadClass(loader, SIGNING_DETAILS) ?: return missing(SIGNING_DETAILS)
        // 33+ 才有带 capability 参数的 mergeLineageWith；本机是 A17，必然存在。
        val mergeLineage = Reflect.findMethods(signingDetails, "mergeLineageWith", paramCount = 2)
            .firstOrNull { it.parameterTypes[0] == signingDetails }
        val checkCapability = Reflect.findMethods(signingDetails, "checkCapability", paramCount = 2)
            .firstOrNull { it.parameterTypes[0] == signingDetails }
        if (mergeLineage == null || checkCapability == null) {
            missing("$SIGNING_DETAILS#mergeLineageWith/checkCapability")
        }
        mergeLineage?.isAccessible = true
        checkCapability?.isAccessible = true
        var count = 0
        for (name in listOf("addPackage", "removePackage")) {
            val methods = Reflect.findMethods(clazz, name, paramCount = 1)
            if (methods.isEmpty()) {
                missing("$SHARED_USER_SETTING#$name(PackageSetting)")
                continue
            }
            count += methods.count { method ->
                HookRuntime.hook(method, "core_patch_shared_user/$SHARED_USER_SETTING#$name") { chain ->
                    if (!settings.isOn(OP_SHARED_USER) || mergeLineage == null || checkCapability == null) {
                        return@hook chain.proceed()
                    }
                    reSignSharedUser(chain.thisObject, chain.args.getOrNull(0), name, checkCapability, mergeLineage)
                    chain.proceed()
                }
            }
        }
        return count
    }

    /**
     * 增删前后的那次「现存成员」扫描。返回 void 语义：任何一步拿不到句柄就什么都不改。
     *
     * `addPackage` 时目标包已在集合里（宿主在 `before` 阶段还没加进去，因此要靠
     * `toAdd.equals(p)` 命中已存在项）；`removePackage` 时目标包仍在集合里，需跳过它本身。
     * 两者的扫描逻辑只差这一个布尔条件，与上游保持一致。
     */
    private fun reSignSharedUser(
        sharedUser: Any?,
        target: Any?,
        name: String,
        checkCapability: Method,
        mergeLineage: Method,
    ) {
        if (sharedUser == null || target == null) return
        val flags = Reflect.readField(sharedUser, "uidFlags") as? Int ?: return
        if (flags and FLAG_SYSTEM != 0) return
        val packages = Reflect.readField(sharedUser, "mPackages") ?: return
        val size = Reflect.callWith(packages, "size") as? Int ?: return
        val sharedUserSig = signingDetailsOf(sharedUser) ?: return
        // UID 记录为空（UNKNOWN）时 `checkCapability` 恒假，照着「谱系已断」去重写等于凭空造签名。
        if (!hasSignatures(sharedUserSig)) return
        var picked: Any? = null
        var matched = false
        for (index in 0 until size) {
            val item = Reflect.callWith(packages, "valueAt", index) ?: continue
            var entry = item
            if (item == target || item.equals(target)) {
                // addPackage：命中已存在项说明这次是「重复添加」，取待加入那份的签名（上游同样把
                // `p` 换成 `toAdd`），它才是这次安装真正要用的谱系；
                // removePackage：命中即说明目标包确实要出去，直接跳过它本身。
                matched = true
                if (name == "removePackage") {
                    continue
                }
                entry = target
            }
            val packageSig = signingDetailsOf(entry) ?: return
            if (!hasSignatures(packageSig)) return
            // 现存包里只要还有人持有旧签名（或旧签名仍持有它），谱系就没有断，不动记录。
            // 任何一次判定拿不到结果都按「不断」处理：改坏共享 UID 的签名记录是不可恢复故障。
            val stillRelated = runCatching {
                (checkCapability.invoke(packageSig, sharedUserSig, CAP_NONE) as? Boolean) == true ||
                    (checkCapability.invoke(sharedUserSig, packageSig, CAP_NONE) as? Boolean) == true
            }.getOrDefault(true)
            if (stillRelated) return
            picked = if (picked == null) {
                packageSig
            } else {
                runCatching { mergeLineage.invoke(picked, packageSig, CAP_RESTRICTED) }.getOrNull() ?: return
            }
        }
        if (!matched || picked == null) return
        // 到这里说明「UID 记录里的签名已经不属于任何一个现存成员」，重写记录才能继续装包。
        ModuleLog.warn("core_patch_shared_user: rewriting $SHARED_USER_SETTING signature during $name")
        writeSigningDetails(sharedUser, picked)
    }

    /** 是否为「真有两把签名」的对象；`UNKNOWN` 与空数组都算没有。 */
    private fun hasSignatures(signingDetails: Any?): Boolean =
        (Reflect.callWith(signingDetails, "hasSignatures") as? Boolean) == true

    /** `PackageSetting` 与 `SharedUserSetting` 都经 `signatures.mSigningDetails` 取签名。 */
    private fun signingDetailsOf(setting: Any?): Any? {
        if (setting == null) return null
        val signatures = Reflect.readField(setting, "signatures") ?: return null
        return Reflect.readField(signatures, "mSigningDetails")
    }

    private fun writeSigningDetails(setting: Any, value: Any): Boolean {
        val signatures = Reflect.readField(setting, "signatures") ?: return false
        val field = Reflect.findField(signatures.javaClass, "mSigningDetails") ?: return false
        return if (UnsafeAccess.isAvailable) {
            UnsafeAccess.putObjectField(field, signatures, value)
        } else {
            Reflect.writeField(field, signatures, value)
        }
    }

    /**
     * 「沿用已安装应用的签名」，以及覆盖安装真正会走的两条 key-set 分支。
     *
     * ## 为什么这一处也必须补
     *
     * 宿主在 `ReconcilePackageUtils#reconcilePackages` 里**先**问
     * `KeySetManagerService#shouldCheckUpgradeKeySetLocked`：一旦判真，就**完全跳过**
     * `verifySignatures`，改问 `checkUpgradeKeySetLocked`；两者判否时抛
     * `ReconcileFailure(-7, "… upgrade keys do not match the previously installed version")`。
     * 也就是说 [installSignatureOverride] 伪造的 `checkCapability` 对声明了
     * `upgrade-key-set` 的应用（Google 系与部分国内大应用）根本不会被问到 ——
     * 这正是「核心破解明明开了，某些应用还是覆盖不了不同签名」的第二条真实原因。
     *
     * 旁路必须**成对**：单独放开 `should` 只是把判定搬到 `check`，单独放开 `check` 则永远
     * 轮不到它。这里用同一个判据（开关 + 用户安装帧）在两个方法里各自判定，
     * 因为 `check` 只会在 `should` 判真时被调用，两条判断天然一致，不需要跨调用传状态。
     *
     * ## 与上游的分工
     *
     * 上游把这两处和 `doesSignatureMatchForPermissions` 一起挂在 `use_pre_signature` 上。
     * 本模块把 key-set 旁路并入「允许覆盖安装不同签名」（用户的实际诉求就是覆盖安装），
     * `use_pre_signature` 则单独保留为下面这个更高权限的子项。
     *
     * ## 权限签名延续单独成项
     *
     * `doesSignatureMatchForPermissions` 决定的是「新包能否继续持有别人声明的
     * `signature` / `signature\|privileged` 权限」，风险比覆盖安装高一档，
     * 因此它只认新子项，绝不被 `override_signature` 顺带打开（capability 4 也仍在
     * [CorePatchSignaturePolicy] 的永不伪造名单里）。判据沿用上游那条最硬的约束：
     * **包名逐字相同**才放行，避免换成另一个包来冒充。
     */
    private fun installPreSignature(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        // 1) key-set 旁路：归「允许覆盖安装不同签名」，但**只放开 `checkUpgradeKeySetLocked`**。
        //
        // 宿主 `ReconcilePackageUtils:88` 的结构是
        // `if (shouldCheckUpgradeKeySetLocked(...)) { if (!checkUpgradeKeySetLocked(...)) throw -7 }
        //  else { verifySignatures(...) }`。
        // 所以**绝不能**把 `should` 也伪造成真：一旦为真，`else` 那个分支就整体不执行，
        // `verifySignatures` 再也不会被调用，本模块精心收敛的能力白名单
        // （只放 1/8、永不放 4/16）会被这条旁路无声绕过 —— 变成「所有安装都不验签」。
        // 上游是成对放开的（它本来就打算全放开），这里不照抄。
        //
        // 只挂 `check` 的覆盖面正好是需要的：`should` 仍由宿主判定（只有真在用
        // `upgrade-key-set` 的应用才为真），这类应用原本在 `check` 判否时抛
        // `-7 "upgrade keys do not match the previously installed version"`，
        // 而这条路径压根不经过 `checkCapability`，正是「核心破解开了仍覆盖不了某些应用」的第二原因。
        val keySet = Reflect.loadClass(loader, KEY_SET_MANAGER)
        if (keySet != null) {
            val methods = Reflect.findMethods(keySet, "checkUpgradeKeySetLocked")
                .filter { it.returnType == Boolean::class.javaPrimitiveType }
            // 调用方分布在 `preparePackage`（5129/5860）与 `reconcilePackages`（:89）两处，
            // 栈上未必有 `preparePackage`，因此门禁用的是 [CorePatchSignaturePolicy.inUserInstallPath]
            // 那三个帧名；开机扫描（processParseResult -> addForInitLI -> reconcilePackages）不在其内，
            // 不会在无人操作时改写系统包的 key-set 判定。
            if (methods.isEmpty()) {
                missing("$KEY_SET_MANAGER#checkUpgradeKeySetLocked")
            } else {
                count += methods.count { method ->
                    HookRuntime.hook(
                        method,
                        "core_patch_override_signature/$KEY_SET_MANAGER#checkUpgradeKeySetLocked",
                    ) { chain ->
                        if (!settings.isOn(OP_OVERRIDE_SIGNATURE)) return@hook chain.proceed()
                        val frames = CorePatchSignaturePolicy.frameNames(Thread.currentThread().stackTrace)
                        if (!CorePatchSignaturePolicy.inUserInstallPath(frames)) return@hook chain.proceed()
                        true
                    }
                }
            }
        } else {
            missing(KEY_SET_MANAGER)
        }
        // 2) 权限签名延续：只认 use_pre_signature，且要求包名逐字相同。
        val helper = Reflect.loadClass(loader, INSTALL_HELPER)
        val match = helper?.let {
            Reflect.findMethods(it, "doesSignatureMatchForPermissions", paramCount = 3)
                .firstOrNull { method ->
                    method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes[0] == String::class.java
                }
        } ?: null
        if (match == null) {
            missing("$INSTALL_HELPER#doesSignatureMatchForPermissions(String,ParsedPackage,int)")
        } else if (HookRuntime.hook(
                match,
                "core_patch_use_pre_signature/$INSTALL_HELPER#doesSignatureMatchForPermissions",
            ) { chain ->
                val original = chain.proceed()
                // 只在宿主本来判「不匹配」时补救，判匹配的调用一律不碰。
                if (original != false) return@hook original
                if (!settings.isOn(OP_USE_PRE_SIGNATURE)) return@hook original
                val installedName = chain.args.getOrNull(0) as? String
                val parsedName = Reflect.callWith(chain.args.getOrNull(1), "getPackageName") as? String
                if (!CorePatchSignaturePolicy.samePackageName(installedName, parsedName)) {
                    return@hook original
                }
                true
            }
        ) count++
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

    /** `ApplicationInfo.FLAG_SYSTEM`：带这个标记的共享 UID 一律不改写签名记录。 */
    private const val FLAG_SYSTEM = 0x1

    /** `checkCapability` 的「只看签名本身、不附加能力位」取值。 */
    private const val CAP_NONE = 0

    /** `SigningDetails.MERGE_RESTRICTED_CAPABILITY`：33+ 合并谱系时的受限模式。 */
    private const val CAP_RESTRICTED = 2

    private fun ThreadLocal<Int>.currentDepth(): Int = get() ?: 0
}
