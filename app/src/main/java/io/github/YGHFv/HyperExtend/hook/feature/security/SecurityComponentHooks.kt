/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.AsyncTask
import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_DEVICE_KEY
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_MANUFACTURER_KEY
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_MODEL_KEY
import io.github.YGHFv.HyperExtend.core.PACKAGE_INSTALLER_SOURCE_PACKAGE_KEY
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayList
import java.util.concurrent.Executor

/**
 * 应用包安装器的云检查与安全提示清理。
 *
 * 靶子均按当前宿主 DEX 的类名与方法形状固定，不按版本号猜混淆名。某个方法在新版本
 * 消失时只记录警告，其余已确认功能照常安装。
 */
internal object PackageInstallerSecurityHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("package_installer_disable_cloud_check")) {
            count += hookCloudCheck(loader)
        }
        if (settings.isOn("package_installer_disable_risk_check")) {
            count += hookBoolean(loader, "B0.c", "q", "installer_risk/secure_verify_enable", false)
            count += hookBoolean(loader, "B0.c", "r", "installer_risk/installerOpenSafetyModel", false)
            count += hookBoolean(loader, "f2.n", "w", "installer_risk/MiuiSettings.Ad", false)
        }
        if (settings.isOn("package_installer_disable_ads")) {
            count += hookBoolean(loader, "B0.c", "o", "installer_ads/ads_enable", false)
            count += hookBoolean(loader, "f2.n", "p", "installer_ads/app_store_recommend", false)
            count += hookBoolean(loader, "f2.n", "r", "installer_ads/virus_scan_install", false)
        }
        if (settings.isOn("package_installer_disable_app_upload")) {
            count += hookUpload(loader)
        }
        if (settings.isOn("package_installer_disable_safe_mode_tip")) {
            count += hookSafeModeTip(loader)
        }
        if (settings.isOn("package_installer_all_system_app")) {
            count += hookAllAsSystemApps(loader)
        }
        if (settings.isOn("package_installer_install_source")) {
            count += hookInstallSource(
                loader,
                settings.string(PACKAGE_INSTALLER_SOURCE_PACKAGE_KEY),
            )
        }
        if (settings.isOn("package_installer_display_more_info")) {
            count += hookDisplayMoreApkInfo(loader)
        }
        if (settings.isOn("package_installer_disable_count_check")) {
            count += hookInt(loader, "com.miui.packageInstaller.model.RiskControlRules",
                "getCurrentLevel", "installer_count/getCurrentLevel", 0)
        }
        if (settings.isOn("package_installer_disable_full_safe_version")) {
            count += hookBoolean(loader, "f2.n", "s",
                "installer_full_safe/installer_full_safe_version", false)
        }
        return count
    }

    private fun hookCloudCheck(loader: ClassLoader): Int {
        val factory = cloudSuccessFactory(loader) ?: return 0
        val clazz = Reflect.loadClass(loader, "com.miui.packageInstaller.e") ?: return missing("cloud/e")
        val method = Reflect.findMethods(clazz, "c", 6)
            .firstOrNull { it.returnType == Any::class.java }
            ?: return missing("cloud/e#c")
        return if (HookRuntime.hook(method, "installer_cloud/e#c") { chain ->
                Reflect.attempt(factory) ?: chain.proceed()
            }
        ) 1 else 0
    }

    private fun cloudSuccessFactory(loader: ClassLoader): (() -> Any?)? {
        val paramsClass = Reflect.loadClass(
            loader,
            "com.miui.packageInstaller.model.CloudParams",
        ) ?: return missingFactory("CloudParams")
        val successClass = Reflect.loadClass(
            loader,
            "com.miui.packageInstaller.model.CloudResult\$Success",
        ) ?: return missingFactory("CloudResult\$Success")
        val paramsConstructor = Reflect.attempt {
            paramsClass.getDeclaredConstructor().also { it.isAccessible = true }
        } ?: return missingFactory("CloudParams()")
        val successConstructor = Reflect.attempt {
            successClass.getDeclaredConstructor(paramsClass).also { it.isAccessible = true }
        } ?: return missingFactory("CloudResult\$Success(CloudParams)")
        return {
            successConstructor.newInstance(paramsConstructor.newInstance())
        }
    }

    private fun hookUpload(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.packageInstaller.util.a") ?: return 0
        val methods = Reflect.declaredMethodsIncludingInherited(clazz).filter {
            it.name in setOf("f", "g") &&
                it.parameterCount == 4 &&
                it.parameterTypes[1].name == "com.miui.packageInstaller.model.ApkInfo" &&
                it.returnType == Void.TYPE
        }
        if (methods.isEmpty()) return missing("installer_upload/util.a#f,g")
        return methods.count { method ->
            HookRuntime.hook(method, "installer_upload/util.a#${method.name}") { null }
        }
    }

    /**
     * 只隐藏安全守护那一行列表项。
     *
     * 上游在当前宿主上会通过父类布尔字段和 `J2.b#a` 收尾，但 55600 的 `J2.b`
     * 已没有 `a`，且有三个布尔字段，无法安全复现旧版字段选择。当前宿主最终渲染入口是
     * `SafeModeTipViewObject#D(ViewHolder)`，所以在其后隐藏该 ViewHolder 的 itemView。
     */
    private fun hookSafeModeTip(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.packageInstaller.ui.listcomponets.SafeModeTipViewObject",
        ) ?: return missing("SafeModeTipViewObject")
        val method = Reflect.findMethods(clazz, "D", 1).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missing("SafeModeTipViewObject#D")
        return if (HookRuntime.hookAfter(method, "installer_safe_tip#D") { chain, original ->
                val holder = chain.args.firstOrNull() ?: return@hookAfter original
                (Reflect.readField(holder, "itemView") as? View)?.visibility = View.GONE
                original
            }
        ) 1 else 0
    }

    /**
     * 当前 55600 宿主里参数为 ApplicationInfo、返回 boolean 的三个判定点。
     *
     * MT 全量 smali 搜索得到 `PackageUtil#u`、OneTrack 的 `f.a#a` 与 `l2.l#d`；
     * 三者都直接检查 `ApplicationInfo.flags` 的系统应用位。
     */
    private fun hookAllAsSystemApps(loader: ClassLoader): Int {
        val targets = listOf(
            "com.android.packageinstaller.PackageUtil" to "u",
            "com.xiaomi.onetrack.f.a" to "a",
            "l2.l" to "d",
        )
        var count = 0
        for ((className, methodName) in targets) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            val methods = Reflect.findMethods(clazz, methodName).filter {
                it.parameterCount == 1 &&
                    it.parameterTypes[0] == ApplicationInfo::class.java &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            for (method in methods) {
                val installed = HookRuntime.hook(method, "installer_system_app/$className#$methodName") { chain ->
                    (chain.args.firstOrNull() as? ApplicationInfo)?.let { info ->
                        info.flags = info.flags or ApplicationInfo.FLAG_SYSTEM
                    }
                    chain.proceed()
                }
                if (installed) count++
            }
        }
        if (count == 0) missing("AllAsSystemApp")
        return count
    }

    /**
     * 安装来源只在 `InstallStart.onCreate` 的调用链内改写。
     *
     * 当前宿主的 `InstallStart` 没有自己的 `getCallingPackage`，两个方法都继承自
     * `android.app.Activity`。直接无条件 hook 会污染整个安装器进程的其他 Activity，
     * 所以两个入口都执行与上游相同的调用栈门禁。
     */
    private fun hookInstallSource(loader: ClassLoader, configured: String): Int {
        val source = configured.trim()
        if (source.isEmpty()) {
            ModuleLog.warn("package_installer: install source is blank; hook skipped")
            return 0
        }
        val activity = Reflect.loadClass(loader, "android.app.Activity")
            ?: return missing("android.app.Activity")
        val methods = listOf("getLaunchedFromPackage", "getCallingPackage").mapNotNull { name ->
            Reflect.findMethods(activity, name, 0).firstOrNull {
                it.returnType == String::class.java
            }
        }
        if (methods.isEmpty()) return missing("Activity install source methods")
        return methods.count { method ->
            HookRuntime.hook(method, "installer_source/Activity#${method.name}") { chain ->
                installSourceResult(chain, source)
            }
        }
    }

    private fun installSourceResult(chain: XposedInterface.Chain, source: String): Any? =
        if (isCalledFromInstallStartOnCreate()) source else chain.proceed()

    private fun isCalledFromInstallStartOnCreate(): Boolean {
        val stack = Thread.currentThread().stackTrace
        for (index in 0 until stack.lastIndex) {
            val current = stack[index]
            val next = stack[index + 1]
            if (current.className.startsWith("com.miui.packageInstaller") &&
                next.className == "com.miui.packageInstaller.InstallStart" &&
                next.methodName == "onCreate"
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 复用宿主自己的应用信息文本，不依赖模块资源或替换整段布局。
     *
     * 基类 `AppInfoViewObject#D` 覆盖普通安装页；风险应用授权页使用独立 ViewHolder，
     * 且不调用基类实现，所以两个 `D` 都要挂。优先写入 `tvDes`，没有该控件时写版本行。
     */
    private fun hookDisplayMoreApkInfo(loader: ClassLoader): Int {
        val apkInfoClass = Reflect.loadClass(
            loader,
            "com.miui.packageInstaller.model.ApkInfo",
        ) ?: return missing("ApkInfo")
        val targets = listOf(
            "com.miui.packageInstaller.ui.listcomponets.AppInfoViewObject",
            "com.miui.packageInstaller.ui.listcomponets.RiskAppInstallAuthAppInfoViewObject",
        )
        var count = 0
        for (className in targets) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            val method = Reflect.findMethods(clazz, "D", 1).firstOrNull {
                it.returnType == Void.TYPE
            } ?: continue
            val installed = HookRuntime.hookAfter(
                method,
                "installer_more_info/$className#D",
            ) { chain, original ->
                val holder = chain.args.firstOrNull() ?: return@hookAfter original
                val apkInfo = Reflect.findFieldByType(chain.thisObject.javaClass, apkInfoClass)
                    ?.get(chain.thisObject) ?: return@hookAfter original
                enhanceApkInfo(apkInfo, holder)
                original
            }
            if (installed) count++
        }
        if (count == 0) missing("AppInfoViewObject#D")
        return count
    }

    private fun enhanceApkInfo(apkInfo: Any, holder: Any) {
        val packageInfo = Reflect.callWith(apkInfo, "getPackageInfo") as? PackageInfo
        val applicationInfo = packageInfo?.applicationInfo
        val fileSize = (Reflect.callWith(apkInfo, "getFileSize") as? Long) ?: 0L
        val fileSizeText = Reflect.callWith(apkInfo, "getFileSizeString") as? String
        val details = PackageInstallerDisplayFormatter.details(
            packageName = (Reflect.callWith(apkInfo, "getPackageName") as? String).orEmpty(),
            versionName = (Reflect.callWith(apkInfo, "getVersionName") as? String).orEmpty(),
            versionCode = (Reflect.callWith(apkInfo, "getVersionCode") as? Int) ?: 0,
            minSdk = applicationInfo?.minSdkVersion,
            targetSdk = applicationInfo?.targetSdkVersion,
            size = fileSizeText?.takeIf { it.isNotBlank() }
                ?: PackageInstallerDisplayFormatter.bytes(fileSize),
            installedVersionName = (
                Reflect.callWith(apkInfo, "getInstalledVersionName") as? String
                ).orEmpty(),
        )
        val description = Reflect.callWith(holder, "getTvDes") as? TextView
        val version = Reflect.callWith(holder, "getTvAppVersion") as? TextView
        val target = description ?: version ?: return
        target.text = details
        target.isSingleLine = false
        target.maxLines = 5
    }

    private fun missing(label: String): Int {
        ModuleLog.warn("package_installer: target missing $label")
        return 0
    }

    private fun missingFactory(label: String): (() -> Any?)? {
        ModuleLog.warn("package_installer: cloud result constructor missing $label")
        return null
    }
}

/** 系统安全检测组件的 Root 状态与应用清单上传。 */
internal object GuardProviderSecurityHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("guard_provider_disable_root_check")) {
            count += patchRootState(loader)
        }
        if (settings.isOn("guard_provider_disable_app_list_upload")) {
            count += hookUpload(loader)
        }
        return count
    }

    private fun patchRootState(loader: ClassLoader): Int {
        // 先完成类初始化，再覆盖 <clinit> 写出的最终结果。
        val clazz = Reflect.attempt { Class.forName("se2", true, loader) } ?: run {
            ModuleLog.warn("guard_provider: target class missing se2")
            return 0
        }
        val field = Reflect.staticFieldOrNull(clazz, "a") ?: run {
            ModuleLog.warn("guard_provider: static root flag missing se2#a")
            return 0
        }
        if (field.type != Boolean::class.javaPrimitiveType) {
            ModuleLog.warn("guard_provider: se2#a is not boolean")
            return 0
        }
        return if (UnsafeAccess.putStaticBoolean(field, false)) 1 else 0
    }

    private fun hookUpload(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "ls1") ?: return 0
        val method = Reflect.findMethods(clazz, "d", 2).firstOrNull {
            it.returnType == String::class.java &&
                it.parameterTypes[1].name == "org.json.JSONObject"
        } ?: run {
            ModuleLog.warn("guard_provider: upload target missing ls1#d")
            return 0
        }
        return if (HookRuntime.hook(method, "guard_provider_upload/ls1#d") { null }) 1 else 0
    }
}

/** Joyose 云端性能策略与 GPU Tuner。 */
internal object JoyoseHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("joyose_disable_cloud_control")) {
            count += hookVoid(loader, "f.h\$a", "run", "joyose_cloud/f.h\$a#run")
        }
        if (settings.isOn("joyose_enable_gpu_tuner")) {
            count += hookBoolean(loader, "f0.g0", "k2", "joyose_gpu/k2", true)
        }
        return count
    }

    private fun hookVoid(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val method = Reflect.findMethods(clazz, methodName, 0)
            .firstOrNull { it.returnType == Void.TYPE } ?: return 0
        return if (HookRuntime.hook(method, label) { null }) 1 else 0
    }
}

/** 安全中心的 Root 环境、安装拦截、联网验证与支付扫描。 */
internal object SecurityCenterSecurityHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("security_center_disable_root_environment")) {
            count += hookBoolean(loader, "w4.l", "o", "security_root/w4.l#o", false)
            count += hookVoid(loader, "w4.l", "M", "security_root/w4.l#M")
            count += hookBoolean(loader, "w4.k", "f", "security_root/w4.k#f", false)
            count += hookBoolean(
                loader,
                "com.miui.antivirus.activity.r0",
                "f",
                "security_root/antivirus.r0#f",
                false,
            )
            count += hookBoolean(
                loader,
                "com.miui.firstaidkit.model.other.SystemRootModel",
                "checkSystemRoot",
                "security_root/SystemRootModel#checkSystemRoot",
                false,
            )
            count += hookBoolean(
                loader,
                "com.miui.securityscan.model.manualitem.PermissionRootModel",
                "checkSystemRoot",
                "security_root/PermissionRootModel#checkSystemRoot",
                false,
            )
            count += hookXsofRootEnvironment(loader)
        }
        if (settings.isOn("security_center_install_intercept")) {
            count += hookBoolean(loader, "ff.c", "t",
                "security_install/permcenter_install_intercept_enabled", false)
        }
        if (settings.isOn("security_center_adb_install_verify")) {
            count += hookAdbVerify(loader)
        }
        if (settings.isOn("security_center_disable_safepay_auto_scan")) {
            count += hookBoolean(loader, "w4.l", "n",
                "security_safepay/key_safepay_auto_scan_state", false)
        }
        if (settings.isOn("security_center_disable_report")) {
            count += hookReportEntry(loader)
        }
        return count
    }

    private fun hookAdbVerify(loader: ClassLoader): Int {
        val workerInstalled = hookAdbWorker(loader)
        if (workerInstalled == 0) return 0
        return workerInstalled + hookAdbDirectLaunch(loader)
    }

    private fun hookAdbWorker(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.permcenter.install.AdbInstallVerifyActivity\$b",
        ) ?: return 0
        val method = Reflect.findMethods(clazz, "a", 1).firstOrNull {
            it.returnType == String::class.java &&
                it.parameterTypes[0].isArray &&
                it.parameterTypes[0].componentType == Void::class.java
        } ?: run {
            ModuleLog.warn("security_center: ADB verify target missing")
            return 0
        }
        return if (HookRuntime.hook(method, "security_adb/AdbInstallVerifyActivity\$b#a") {
                null
            }
        ) 1 else 0
    }

    /**
     * 上游先截获 `z0()`，再手工把已安装 Hook 的 AsyncTask 送进线程池。
     *
     * 当前宿主 `z0()` 同样会做联网检查；如果只让 worker 返回 null，网络请求仍会发出。
     * 所以这里直接复用宿主原有 `$b` 任务，但不调用原 `z0()`。任一反射步骤失败时回退原
     * `z0()`，避免安装验证页面停在无回调状态。
     */
    private fun hookAdbDirectLaunch(loader: ClassLoader): Int {
        val activityClass = Reflect.loadClass(
            loader,
            "com.miui.permcenter.install.AdbInstallVerifyActivity",
        ) ?: return missingHook("security_adb/AdbInstallVerifyActivity")
        val taskClass = Reflect.loadClass(
            loader,
            "com.miui.permcenter.install.AdbInstallVerifyActivity\$b",
        ) ?: return missingHook("security_adb/AdbInstallVerifyActivity\$b")
        val method = Reflect.findMethods(activityClass, "z0", 0).firstOrNull {
            it.returnType == Void.TYPE
        } ?: return missingHook("security_adb/AdbInstallVerifyActivity#z0")
        val constructor = Reflect.attempt {
            taskClass.declaredConstructors.firstOrNull { it.parameterCount == 1 }
                ?.also { it.isAccessible = true }
        } ?: return missingHook("security_adb/AdbInstallVerifyActivity\$b()")
        val taskField = Reflect.findFieldByType(activityClass, taskClass)
            ?: return missingHook("security_adb/AdbInstallVerifyActivity#d")
        val execute = Reflect.findMethods(taskClass, "executeOnExecutor", 2).firstOrNull()
            ?: return missingHook("security_adb/executeOnExecutor")
        val asyncTask = Reflect.loadClass(loader, "android.os.AsyncTask")
            ?: return missingHook("android.os.AsyncTask")
        val executor = Reflect.staticFieldOrNull(asyncTask, "THREAD_POOL_EXECUTOR")
            ?.get(null) as? Executor ?: return missingHook("AsyncTask#THREAD_POOL_EXECUTOR")
        return if (HookRuntime.hook(method, "security_adb/AdbInstallVerifyActivity#z0") { chain ->
                val activity = chain.thisObject
                val task = Reflect.attempt { constructor.newInstance(activity) }
                val started = task != null &&
                    Reflect.writeField(taskField, activity, task) &&
                    Reflect.attempt {
                        execute.isAccessible = true
                        execute.invoke(task, executor, emptyArray<Void>())
                        true
                    } == true
                if (started) null else chain.proceed()
            }
        ) 1 else 0
    }

    /**
     * XSOF 环境任务链。
     *
     * 当前宿主调用关系：
     * `MiSafetyDetectService.n(Object)` -> 读取 `co.a.e` 判断取消 -> `eo.b.b(co.a)`。
     * 上游先把请求标记为 canceled，再异步调用 RiskAppManager；这样 `n` 不再进入任务分支，
     * 而 `eo.b.c` 仍通过 `content://guard#getRiskAppData` 取回风险数据并回调。
     *
     * 55600 安全中心里 `eo.b` 只有 `b(co.a)` 与私有 `c(co.a)`，只有 `b` 是安全入口；
     * 不能照搬上游“除 unset 外全部调用”的旧版多方法策略。
     */
    private fun hookXsofRootEnvironment(loader: ClassLoader): Int {
        val serviceClass = Reflect.loadClass(
            loader,
            "com.xiaomi.security.xsof.MiSafetyDetectService",
        ) ?: return missingHook("security_root/MiSafetyDetectService")
        val clientClass = Reflect.loadClass(loader, "co.a")
            ?: return missingHook("security_root/ClientApiRequest")
        val riskClass = Reflect.loadClass(loader, "eo.b")
            ?: return missingHook("security_root/RiskAppManager")
        val taskMethod = Reflect.findMethods(serviceClass, "n", 1).firstOrNull {
            it.parameterTypes[0] == Any::class.java && it.returnType == Void.TYPE
        } ?: return missingHook("security_root/MiSafetyDetectService#n")
        val taskTypeField = Reflect.findField(clientClass, "a")?.takeIf {
            it.type == Int::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        } ?: return missingHook("security_root/ClientApiRequest#a")
        val canceledField = Reflect.findField(clientClass, "e")?.takeIf {
            it.type == Boolean::class.javaPrimitiveType && !Modifier.isStatic(it.modifiers)
        } ?: return missingHook("security_root/ClientApiRequest#e")
        val isCanceled = Reflect.findMethods(clientClass, "b", 0).firstOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        } ?: return missingHook("security_root/ClientApiRequest#b")
        val riskField = Reflect.findFieldByType(serviceClass, riskClass)
            ?: return missingHook("security_root/MiSafetyDetectService#RiskAppManager")
        val scanMethod = Reflect.findMethods(riskClass, "b", 1).firstOrNull {
            it.parameterTypes[0] == clientClass && it.returnType == Void.TYPE
        } ?: return missingHook("security_root/RiskAppManager#b")
        return if (HookRuntime.hook(taskMethod, "security_root/MiSafetyDetectService#n") { chain ->
                val request = chain.args.firstOrNull()
                val taskType = Reflect.attempt {
                    if (request != null && clientClass.isInstance(request)) {
                        taskTypeField.getInt(request)
                    } else {
                        -1
                    }
                } ?: -1
                if (taskType != XSOF_ROOT_TASK_TYPE) return@hook chain.proceed()

                val manager = Reflect.attempt { riskField.get(chain.thisObject) }
                if (manager == null) {
                    ModuleLog.warn("security_root: RiskAppManager unavailable; preserving host task")
                    return@hook chain.proceed()
                }

                val marked = UnsafeAccess.putBooleanField(canceledField, request, true) &&
                    Reflect.callWith(request, isCanceled.name) == true
                val scanStarted = Reflect.attempt {
                    scanMethod.isAccessible = true
                    scanMethod.invoke(manager, request)
                    true
                } == true
                if (!marked) {
                    // 取消标记写入失败时，不能让原 n 再次进入 Root 扫描；风险数据请求已直接发出。
                    return@hook null
                }
                if (!scanStarted) {
                    UnsafeAccess.putBooleanField(canceledField, request, false)
                    return@hook chain.proceed()
                }
                chain.proceed()
            }
        ) 1 else 0
    }

    private const val XSOF_ROOT_TASK_TYPE = 0xb

    private fun hookReportEntry(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.appmanager.fragment.ApplicationsDetailsFragment",
        ) ?: return 0
        val method = Reflect.findMethods(clazz, "q2", 2).firstOrNull {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == Uri::class.java
        } ?: run {
            ModuleLog.warn("security_center: report entry target missing")
            return 0
        }
        return if (HookRuntime.hookReturning(method, "security_report/ApplicationsDetailsFragment#q2", false)) {
            1
        } else {
            0
        }
    }
}

/** 应用商店安全页、安装监控与启动推送。 */
internal object MarketSecurityHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("market_bypass_risk_check")) {
            count += hookRiskCheck(loader)
        }
        if (settings.isOn("market_disable_package_monitor")) {
            count += hookVoid(
                loader,
                "com.xiaomi.market.receiver.MyPackageMonitor",
                "init",
                "market_monitor/MyPackageMonitor#init",
            )
        }
        if (settings.isOn("market_disable_start_push_dialog")) {
            count += hookBoolean(
                loader,
                "com.xiaomi.market.data.NotificationRecallController",
                "checkAndTryShowDialog",
                "market_push/checkAndTryShowDialog",
                false,
            )
            count += hookBoolean(
                loader,
                "com.xiaomi.market.data.NotificationRecallController",
                "tryShowDialog",
                "market_push/tryShowDialog",
                false,
            )
        }
        if (settings.isOn("market_disable_ads")) {
            count += hookMarketAds(loader)
        }
        if (settings.isOn("market_device_modify")) {
            count += hookMarketDeviceIdentity(loader, settings)
        }
        return count
    }

    private fun hookMarketAds(loader: ClassLoader): Int {
        val appDetail = Reflect.loadClass(
            loader,
            "com.xiaomi.market.common.network.retrofit.response.bean.AppDetailV3",
            "com.xiaomi.market.retrofit.response.bean.AppDetailV3",
        )
        val detailSplash = Reflect.loadClass(
            loader,
            "com.xiaomi.market.ui.splash.DetailSplashAdManager",
        )
        val splash = Reflect.loadClass(loader, "com.xiaomi.market.ui.splash.SplashManager")
        var count = 0

        val appDetailTrue = listOf(
            "isBrowserMarketAdOff",
            "isBrowserSourceFileAdOff",
            "supportShowCompat64bitAlert",
        )
        val appDetailFalse = listOf(
            "isInternalAd",
            "needShowAds",
            "needShowAdsWithSourceFile",
            "showComment",
            "showRecommend",
            "showTopBanner",
            "showTopVideo",
            "equals",
            "getShowOpenScreenAd",
            "hasGoldLabel",
            "isBottomButtonLayoutType",
            "isPersonalization",
            "isTopButtonLayoutType",
            "isTopSingleTabMultiButtonType",
            "needShowGrayBtn",
            "needShowPISafeModeStyle",
            "supportAutoLoadDeepLink",
            "supportShowCompatAlert",
            "supportShowCompatChildForbidDownloadAlert",
        )
        val splashFalse = listOf(
            "canShowSplash",
            "needShowSplash",
            "needRequestFocusVideo",
            "isPassiveSplashAd",
        )
        val detailSplashFalse = listOf(
            "canRequestSplashAd",
            "isRequesting",
            "isOpenFromMsa",
        )

        for (name in appDetailTrue) {
            count += hookBooleanMethods(appDetail, name, "market_ads/AppDetailV3#$name", true)
        }
        for (name in appDetailFalse) {
            count += hookBooleanMethods(appDetail, name, "market_ads/AppDetailV3#$name", false)
        }
        for (name in splashFalse) {
            count += hookBooleanMethods(splash, name, "market_ads/SplashManager#$name", false)
        }
        for (name in detailSplashFalse) {
            count += hookBooleanMethods(
                detailSplash,
                name,
                "market_ads/DetailSplashAdManager#$name",
                false,
            )
        }
        detailSplash?.let { clazz ->
            val methods = Reflect.findMethods(clazz, "tryToRequestSplashAd").filter {
                it.returnType == Void.TYPE
            }
            count += methods.count {
                HookRuntime.hook(it, "market_ads/DetailSplashAdManager#tryToRequestSplashAd") { null }
            }
        }
        if (count == 0) missingHook("market_ads/all")
        return count
    }

    private fun hookBooleanMethods(
        clazz: Class<*>?,
        name: String,
        label: String,
        value: Boolean,
    ): Int {
        if (clazz == null) return 0
        return Reflect.findMethods(clazz, name).filter {
            it.returnType == Boolean::class.javaPrimitiveType ||
                it.returnType == Boolean::class.javaObjectType
        }.count { HookRuntime.hookAfter(it, label) { _, _ -> value } }
    }

    /**
     * Build 身份只在 `MarketApp` 构造前写入，进程内后续消费者会读到同一组值。
     *
     * 当前宿主 `MarketApp` 只有一个无参构造器；DEVICE/MODEL/MANUFACTURER 都是
     * `Build` 的 static final String，普通反射写入不会持久，因此必须走 Unsafe。
     */
    private fun hookMarketDeviceIdentity(loader: ClassLoader, settings: HookSettings): Int {
        val overrides = linkedMapOf(
            "DEVICE" to settings.string(MARKET_DEVICE_DEVICE_KEY).trim(),
            "MODEL" to settings.string(MARKET_DEVICE_MODEL_KEY).trim(),
            "MANUFACTURER" to settings.string(MARKET_DEVICE_MANUFACTURER_KEY).trim(),
        ).filterValues { it.isNotEmpty() }
        if (overrides.isEmpty()) {
            ModuleLog.warn("market_device: all overrides are blank; hook skipped")
            return 0
        }
        val appClass = Reflect.loadClass(loader, "com.xiaomi.market.MarketApp")
            ?: return missingHook("market_device/MarketApp")
        val constructor = Reflect.attempt {
            appClass.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
        } ?: return missingHook("market_device/MarketApp()")
        val buildClass = Reflect.loadClass(loader, "android.os.Build")
            ?: return missingHook("market_device/android.os.Build")
        val fields = overrides.keys.mapNotNull { name ->
            val field = Reflect.staticFieldOrNull(buildClass, name) ?: return@mapNotNull null
            if (field.type != String::class.java || !Modifier.isStatic(field.modifiers)) {
                return@mapNotNull null
            }
            name to field
        }.toMap()
        if (fields.size != overrides.size) {
            return missingHook("market_device/Build fields")
        }
        return if (HookRuntime.hook(constructor, "market_device/MarketApp#<init>") { chain ->
                applyBuildIdentity(fields, overrides)
                chain.proceed()
            }
        ) 1 else 0
    }

    private fun applyBuildIdentity(fields: Map<String, Field>, overrides: Map<String, String>) {
        val originals = linkedMapOf<String, String>()
        val changed = ArrayList<String>(overrides.size)
        for ((name, field) in fields) {
            val original = Reflect.attempt { field.get(null) as? String } ?: return rollbackBuildIdentity(
                fields,
                originals,
                overrides,
                changed,
            )
            originals[name] = original
            val replacement = overrides[name] ?: continue
            if (replacement == original) continue
            // 先登记再写：底层可能已写入后验证失败，回滚必须覆盖这个字段。
            changed += name
            val persisted = UnsafeAccess.putStaticObject(field, replacement) &&
                Reflect.attempt { field.get(null) == replacement } == true
            if (!persisted) {
                ModuleLog.warn("market_device: failed to write Build.$name")
                return rollbackBuildIdentity(fields, originals, overrides, changed)
            }
        }
    }

    private fun rollbackBuildIdentity(
        fields: Map<String, Field>,
        originals: Map<String, String>,
        overrides: Map<String, String>,
        changed: List<String>,
    ) {
        for (name in changed.asReversed()) {
            val field = fields[name] ?: continue
            val original = originals[name] ?: continue
            val replacement = overrides[name] ?: continue
            // 宿主或其他模块若已改写该字段，不用旧原值覆盖第三方写入。
            if (Reflect.attempt { field.get(null) == replacement } != true) continue
            if (!UnsafeAccess.putStaticObject(field, original) ||
                Reflect.attempt { field.get(null) == original } != true
            ) {
                ModuleLog.warn("market_device: rollback failed for Build.$name")
            }
        }
    }

    private fun hookRiskCheck(loader: ClassLoader): Int {
        val totalClass = Reflect.loadClass(
            loader,
            "com.xiaomi.market.common.component.componentbeans.TotalResult",
        ) ?: return 0
        val excellent = Reflect.attempt {
            Reflect.staticFieldOrNull(totalClass, "TOTAL_EXCELLENT")?.get(null)
        } ?: run {
            ModuleLog.warn("market_risk: TOTAL_EXCELLENT missing")
            return 0
        }
        var count = 0
        val rulesClass = Reflect.loadClass(
            loader,
            "com.xiaomi.market.common.component.componentbeans.AppSecurityCheckRules\$Companion",
        )
        val totalMethod = rulesClass?.let { clazz ->
            Reflect.findMethods(clazz, "getTotalResult", 4).firstOrNull {
                it.returnType == totalClass
            }
        }
        if (totalMethod != null &&
            HookRuntime.hookReturning(totalMethod, "market_risk/getTotalResult", excellent)
        ) {
            count++
        }

        val riskView = Reflect.loadClass(
            loader,
            "com.xiaomi.market.business_ui.main.mine.app_security.check_page.risk_app.AppSecurityRiskAppView",
        )
        val updateMethod = riskView?.let { clazz ->
            Reflect.findMethods(clazz, "updateResultStatus", 2).firstOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
        if (updateMethod != null) {
            val installed = HookRuntime.hook(updateMethod, "market_risk/updateResultStatus") { chain ->
                val args = chain.args.toTypedArray()
                args[0] = 0
                chain.proceed(args)
            }
            if (installed) count++
        }

        val riskField = riskView?.let { Reflect.findField(it, "riskModelList") }
        val imageMethod = riskView?.let { clazz ->
            Reflect.findMethods(clazz, "setListOpenImageStatus", 0)
                .firstOrNull { it.returnType == Void.TYPE }
        }
        if (imageMethod != null && riskField != null) {
            val installed = HookRuntime.hook(imageMethod, "market_risk/setListOpenImageStatus") { chain ->
                Reflect.writeField(riskField, chain.thisObject, ArrayList<Any>())
                chain.proceed()
            }
            if (installed) count++
        }

        val manager = Reflect.loadClass(
            loader,
            "com.xiaomi.market.business_ui.main.mine.app_security.AppSecurityCheckManager",
        )
        val mineMethod = manager?.let { clazz ->
            Reflect.findMethods(clazz, "getRiskResultTxtByMine", 2).firstOrNull {
                it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
            }
        }
        if (mineMethod != null) {
            val installed = HookRuntime.hook(mineMethod, "market_risk/getRiskResultTxtByMine") { chain ->
                val args = chain.args.toTypedArray()
                if (args.size > 1) args[1] = 0
                chain.proceed(args)
            }
            if (installed) count++
        }

        val pageMethod = manager?.let { clazz ->
            Reflect.findMethods(clazz, "getSecurityResultTxtByPage", 3).firstOrNull {
                it.parameterTypes[0] == totalClass &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Int::class.javaPrimitiveType
            }
        }
        if (pageMethod != null) {
            val installed = HookRuntime.hook(pageMethod, "market_risk/getSecurityResultTxtByPage") { chain ->
                val args = chain.args.toTypedArray()
                args[0] = excellent
                args[2] = 0
                chain.proceed(args)
            }
            if (installed) count++
        }
        return count
    }

    private fun hookVoid(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val method = Reflect.findMethods(clazz, methodName, 0)
            .firstOrNull { it.returnType == Void.TYPE } ?: return 0
        return if (HookRuntime.hook(method, label) { null }) 1 else 0
    }
}

private fun hookBoolean(
    loader: ClassLoader,
    className: String,
    methodName: String,
    label: String,
    value: Boolean,
): Int {
    val clazz = Reflect.loadClass(loader, className) ?: return missingHook(label)
    val method = Reflect.findMethods(clazz, methodName)
        .filter { it.returnType == Boolean::class.javaPrimitiveType || it.returnType == Boolean::class.javaObjectType }
        .singleOrNull()
    if (method == null) return missingHook(label)
    return if (HookRuntime.hookReturning(method, label, value)) 1 else 0
}

private fun hookInt(
    loader: ClassLoader,
    className: String,
    methodName: String,
    label: String,
    value: Int,
): Int {
    val clazz = Reflect.loadClass(loader, className) ?: return missingHook(label)
    val method = Reflect.findMethods(clazz, methodName)
        .singleOrNull { it.returnType == Int::class.javaPrimitiveType }
        ?: return missingHook(label)
    return if (HookRuntime.hookReturning(method, label, value)) 1 else 0
}

private fun hookVoid(
    loader: ClassLoader,
    className: String,
    methodName: String,
    label: String,
): Int {
    val clazz = Reflect.loadClass(loader, className) ?: return missingHook(label)
    val method: Method = Reflect.findMethods(clazz, methodName)
        .singleOrNull { it.returnType == Void.TYPE }
        ?: return missingHook(label)
    return if (HookRuntime.hook(method, label) { null }) 1 else 0
}

private fun missingHook(label: String): Int {
    ModuleLog.warn("hook target missing: $label")
    return 0
}
