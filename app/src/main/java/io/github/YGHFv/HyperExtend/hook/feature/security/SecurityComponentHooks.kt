/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.security

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.res.Resources
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.os.Message
import android.os.UserHandle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.ArrayMap
import android.util.AttributeSet
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_DEVICE_KEY
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_MANUFACTURER_KEY
import io.github.YGHFv.HyperExtend.core.MARKET_DEVICE_MODEL_KEY
import io.github.YGHFv.HyperExtend.core.PACKAGE_INSTALLER_SOURCE_PACKAGE_KEY
import io.github.YGHFv.HyperExtend.core.SECURITY_CENTER_APP_SORT_KEY
import io.github.YGHFv.HyperExtend.core.SECURITY_CENTER_SIDEBAR_LINE_DARK_KEY
import io.github.YGHFv.HyperExtend.core.SECURITY_CENTER_SIDEBAR_LINE_DEFAULT_KEY
import io.github.YGHFv.HyperExtend.core.SECURITY_CENTER_SIDEBAR_LINE_LIGHT_KEY
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
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

/**
 * 小米信任服务的 MRM 风险检测。
 *
 * 当前宿主的唯一靶点是 `Y0.h#b(): boolean`：方法内部初始化
 * `vendor.xiaomi.hardware.mrm.IMrm`，失败时返回 false；两个上游字符串
 * `MiTrustService/statusEventHandle` 与 `try init mrmd Service` 都位于该方法。
 * 安装前直接返回 false，阻止 MRM 服务初始化与后续风险事件处理。
 */
internal object TrustServiceHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("trustservice_disable_mrm")) return 0
        val clazz = Reflect.loadClass(loader, "Y0.h")
            ?: return missingHook("trustservice/Y0.h")
        val methods = Reflect.findMethods(clazz, "b", 0).filter {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (methods.size != 1) return missingHook("trustservice/Y0.h#b")
        return if (HookRuntime.hookReturning(methods.single(), "trustservice/Y0.h#b", false)) {
            1
        } else {
            0
        }
    }
}

/**
 * 小米云服务的云同步类别列表。
 *
 * 当前宿主的唯一靶点是 `Y4.a$c#a(Context, List): void`，方法内部按短信、
 * 通话、浏览器、创作等条件从传入列表里原地 `remove`。上游对 void 方法设置
 * `null` 没有实际返回值意义，真实语义是跳过原方法；因此这里直接跳过，
 * 让宿主保留完整的同步类别列表。
 */
internal object CloudServiceHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("micloud_service_list")) return 0
        val clazz = Reflect.loadClass(loader, "Y4.a\$c")
            ?: return missingHook("cloudservice/Y4.a\$c")
        val method = Reflect.findMethods(clazz, "a", 2).singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == List::class.java
        } ?: return missingHook("cloudservice/Y4.a\$c#a")
        return if (HookRuntime.hook(method, "cloudservice/Y4.a\$c#a") { null }) 1 else 0
    }
}

/**
 * 小米统计服务的使用数据收集与网络状态上报。
 *
 * 当前宿主的靶点与上游一一对应：
 * - 上游通过 `Don't collect usage below Android KK 4.4` 字符串定位**所有**使用量
 *   事件构造入口；当前宿主该字符串落在两个方法上，必须同时拦截：
 *   `a.b.a.e.h0.y#g(long, long, boolean): JSONObject` 与
 *   `a.b.a.f.f.m#f(long, long): JSONObject`，返回 null 跳过事件内容构造。
 * - `a.b.a.a.a.c.a#V(Context): String` 是网络状态（`WIFI/5G/4G/3G/2G/NONE`），
 *   固定返回 `NONE`。
 * - `a.b.a.a.a.c.a#l0(): boolean` 是网络连通判断，固定返回 false。
 *
 * `NetworkUtil` 字符串在当前宿主只出现在上述 `V` 与 `l0` 两个方法中；上游
 * 旧签名的 `NetworkUtil#a(Context): int` 在此版本会误伤同类的 `K(Context): int`
 * （`getCoreVersionCode`），因此不按旧签名硬套，避免把版本号上报改成 0。
 */
internal object AnalyticsHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("analytics_fuck_guard")) return 0
        var count = 0
        count += hookUsageJson(loader)
        count += hookNetworkState(loader)
        return count
    }

    private fun hookUsageJson(loader: ClassLoader): Int {
        var count = 0
        val legacy = Reflect.loadClass(loader, "a.b.a.e.h0.y")?.let { clazz ->
            Reflect.findMethods(clazz, "g", 3).singleOrNull {
                it.returnType.name == "org.json.JSONObject" &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
            }?.let { method ->
                if (HookRuntime.hook(method, "analytics/usage#g") { null }) 1 else 0
            }
        } ?: 0
        count += legacy
        val variant = Reflect.loadClass(loader, "a.b.a.f.f.m")?.let { clazz ->
            Reflect.findMethods(clazz, "f", 2).singleOrNull {
                it.returnType.name == "org.json.JSONObject" &&
                    it.parameterTypes[0] == Long::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Long::class.javaPrimitiveType
            }?.let { method ->
                if (HookRuntime.hook(method, "analytics/usage#f") { null }) 1 else 0
            }
        } ?: 0
        count += variant
        if (count == 0) missingHook("analytics/usage")
        return count
    }

    private fun hookNetworkState(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "a.b.a.a.a.c.a")
            ?: return missingHook("analytics/a.b.a.a.a.c.a")
        var count = 0
        val networkState = Reflect.findMethods(clazz, "V", 1).singleOrNull {
            it.returnType == String::class.java &&
                it.parameterTypes[0] == Context::class.java
        }
        if (networkState != null &&
            HookRuntime.hookReturning(networkState, "analytics/network_state#V", "NONE")
        ) {
            count++
        }
        val connected = Reflect.findMethods(clazz, "l0", 0).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (connected != null &&
            HookRuntime.hookReturning(connected, "analytics/network_connected#l0", false)
        ) {
            count++
        }
        if (count == 0) missingHook("analytics/network")
        return count
    }
}

/** 设置云控数据更新任务（宿主包名为 HTMLViewer 的系统服务）。 */
internal object HtmlViewerHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("html_viewer_disable_cloud_control")) return 0
        val clazz = Reflect.loadClass(loader, "com.android.settings.cloud.JobTask")
            ?: return missingHook("htmlviewer/JobTask")
        val method = Reflect.findMethods(clazz, "updateCloudAllData", 0).singleOrNull {
            it.returnType == Void.TYPE
        } ?: return missingHook("htmlviewer/JobTask#updateCloudAllData")
        return if (HookRuntime.hook(method, "htmlviewer/updateCloudAllData") { null }) 1 else 0
    }
}

/** 安全中心的安全、隐私、维护、电池与安装相关功能。 */
internal object SecurityCenterSecurityHooks {
    private const val APP_DISABLE_MENU_ID = 666
    private const val APP_INFO_MENU_ID = 667
    private const val APP_MANAGER_MENU_ID = 668
    private const val BEAUTY_AUTO_LIGHT_KEY = "preference_key_beauty_auto_light"

    private const val APP_DETAIL_VERSION_CODE = "hyperextend_app_version_code"
    private const val APP_DETAIL_UID = "hyperextend_app_uid"
    private const val APP_DETAIL_DATA_DIR = "hyperextend_app_data_dir"
    private const val APP_DETAIL_SOURCE_DIR = "hyperextend_app_source_dir"
    private const val APP_DETAIL_MIN_SDK = "hyperextend_app_min_sdk"
    private const val APP_DETAIL_TARGET_SDK = "hyperextend_app_target_sdk"
    private const val APP_DETAIL_MARKET = "hyperextend_app_open_market"
    private const val APP_DETAIL_LAUNCH = "hyperextend_app_launch"

    private val APP_DETAIL_ROWS = listOf(
        APP_DETAIL_VERSION_CODE to "版本号",
        APP_DETAIL_UID to "UID",
        APP_DETAIL_DATA_DIR to "数据目录",
        APP_DETAIL_SOURCE_DIR to "APK 路径",
        APP_DETAIL_MIN_SDK to "最低 SDK",
        APP_DETAIL_TARGET_SDK to "目标 SDK",
        APP_DETAIL_MARKET to "在应用商店打开",
        APP_DETAIL_LAUNCH to "启动应用",
    )
    private val APP_DETAIL_VALUE_KEYS = setOf(
        APP_DETAIL_VERSION_CODE,
        APP_DETAIL_UID,
        APP_DETAIL_DATA_DIR,
        APP_DETAIL_SOURCE_DIR,
        APP_DETAIL_MIN_SDK,
        APP_DETAIL_TARGET_SDK,
    )
    private val APP_DETAIL_ACTION_KEYS = setOf(APP_DETAIL_MARKET, APP_DETAIL_LAUNCH)
    private val PROTECTED_APP_PACKAGES = setOf(
        "com.lbe.security.miui",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.miui.home",
        "com.android.systemui",
        "com.android.settings",
    )

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("security_center_disable_root_environment")) {
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
        if (settings.isOn("security_center_lock_score")) {
            count += hookSecurityScore(loader)
        }
        if (settings.isOn("security_center_disable_malicious_app_notification")) {
            count += hookMaliciousAppNotification(loader)
        }
        if (settings.isOn("security_center_remove_open_app_confirmation_popup")) {
            count += hookOpenAppConfirmation(loader)
        }
        if (settings.isOn("security_center_disable_auto_turn_accessibility_off")) {
            count += hookAccessibilityAutoDisable(loader)
        }
        if (settings.isOn("security_center_applock_pin_scramble")) {
            count += hookAppLockPinScramble(loader)
        }
        if (settings.isOn("security_center_unlock_car_sickness")) {
            count += hookCarSickness(loader)
        }
        val appSort = settings.string(SECURITY_CENTER_APP_SORT_KEY).toIntOrNull()
        if (appSort != null && appSort in 1..5) {
            count += hookAppDefaultSort(loader, appSort)
        }
        if (settings.isOn("security_center_app_restrict")) {
            count += hookAppRestrict(loader)
        }
        if (settings.isOn("security_center_app_disable")) {
            count += hookAppDisable(loader)
        }
        if (settings.isOn("security_center_app_details")) {
            count += hookAppDetails(loader)
        }
        if (settings.isOn("security_center_app_default_setting")) {
            count += hookAppDefaultSetting(loader)
        }
        if (settings.isOn("security_center_aosp_app_info")) {
            count += hookAospAppInfoEntry(loader)
        }
        if (settings.isOn("security_center_aosp_app_manager")) {
            count += hookAospAppManagerEntry(loader)
        }
        if (settings.isOn("security_center_disable_root_check_item")) {
            count += hookBoolean(loader, "w4.l", "o", "security_root/root_item#o", false)
        }
        if (settings.isOn("security_center_simlock_auth_bypass")) {
            count += hookSimLockAuth(loader)
        }
        if (settings.isOn("security_center_hide_simlock_success_dialog")) {
            count += hookSimLockSuccessDialog(loader)
        }
        if (settings.isOn("security_center_hide_xopt_tip")) {
            count += hookBoolean(
                loader,
                "com.miui.permcenter.v",
                "j",
                "security_privacy/xopt_tip#j",
                true,
            )
        }
        if (settings.isOn("security_center_lock_thumbnail_blur")) {
            count += hookThumbnailBlur(loader)
        }
        if (settings.isOn("security_center_simplify_home")) {
            count += hookSimplifyHome(loader)
        }
        if (settings.isOn("security_center_disable_miaoxiang_power_saver")) {
            count += hookMiaoxiangPowerSaver(loader)
        }
        if (settings.isOn("security_center_disable_offline_info")) {
            count += hookOfflineInfo(loader)
        }
        if (settings.isOn("security_center_hide_low_battery_warning")) {
            count += hookLowBatteryWarning(loader)
        }
        if (settings.isOn("security_center_unlock_low_temp_endurance")) {
            count += hookBoolean(
                loader,
                "oh.g",
                "t",
                "security_battery/low_temp_endurance#t",
                true,
            )
        }
        if (settings.isOn("security_center_show_battery_temperature")) {
            count += hookBatteryTemperature(loader)
        }
        if (settings.isOn("security_center_show_battery_health")) {
            count += hookBatteryHealth(loader)
        }
        if (settings.isOn("security_center_unlock_smart_charge")) {
            count += hookSmartCharge(loader)
        }
        if (settings.isOn("security_center_unlock_super_wireless_charge")) {
            count += hookSuperWirelessCharge(loader)
        }
        if (settings.isOn("security_center_unlock_screen_time")) {
            count += hookScreenUsedTime(loader)
        }
        if (settings.isOn("security_center_power_consumption_ranking")) {
            count += hookPowerConsumptionRanking(loader)
        }
        if (settings.isOn("security_center_show_more_battery_info")) {
            count += hookMoreBatteryInfo(loader)
        }
        if (settings.isOn("security_center_skip_countdown")) {
            count += hookSkipCountdown(loader)
        }
        if (settings.isOn("security_center_disable_remove_screen_hold_on")) {
            count += hookBoolean(
                loader,
                "com.miui.gamebooster.utils.o0",
                "g0",
                "security_sidebar_video/remove_screen_hold_on",
                false,
            )
        }
        if (settings.isOn("security_center_remove_vb_temp_limit")) {
            count += hookVideoTemperatureLimit(loader)
        }
        if (settings.isOn("security_center_dolby_open")) {
            count += hookVoid(
                loader,
                "com.miui.gamebooster.service.DockWindowManagerService",
                "p2",
                "security_sidebar_video/dolby_permission",
            )
        }
        if (settings.isOn("security_center_unlock_memc")) {
            count += hookBoolean(
                loader,
                "mb.x",
                "F",
                "security_sidebar_video/memc",
                true,
            )
        }
        if (settings.isOn("security_center_unlock_s_resolution")) {
            count += hookBoolean(
                loader,
                "mb.x",
                "H",
                "security_sidebar_video/super_resolution",
                true,
            )
        }
        if (settings.isOn("security_center_unlock_enhance_contours")) {
            count += hookBoolean(
                loader,
                "mb.x",
                "I",
                "security_sidebar_video/enhance_contours",
                true,
            )
        }
        if (settings.isOn("security_center_unlock_new_vb")) {
            count += hookBoolean(
                loader,
                "mb.q",
                "g",
                "security_sidebar_video/theatre_mode",
                true,
            )
        }
        if (settings.isOn("security_center_game_performance_wild_mode")) {
            count += hookBoolean(
                loader,
                "ba.c",
                "r",
                "security_sidebar_game/wild_mode",
                true,
            )
        }
        if (settings.isOn("security_center_remove_game_toast")) {
            count += hookGameToast(loader)
        }
        if (settings.isOn("security_center_unlock_gun_service")) {
            count += hookGunService(loader)
        }
        if (settings.isOn("security_center_remove_macro_black_list")) {
            count += hookMacroBlackList(loader)
        }
        if (settings.isOn("security_center_remove_conversation_bubble_settings_restriction")) {
            count += hookBubbleSettingsRestriction(loader)
        }
        if (settings.isOn("security_center_unlock_side_hide_freeform")) {
            count += hookSideHideFreeform(loader)
        }
        if (settings.isOn("security_center_beauty_light_auto")) {
            count += hookBeautyLightAuto(loader)
        }
        if (settings.isOn("security_center_beauty_face")) {
            count += hookBooleanParam(
                loader,
                "q8.i",
                "M",
                0,
                "security_beauty/face",
                true,
            )
        }
        if (settings.isOn("security_center_beauty_pc")) {
            count += hookBooleanParam(
                loader,
                "q8.i",
                "e0",
                0,
                "security_beauty/portrait_center",
                true,
            )
        }
        if (settings.isOn("security_center_beauty_privacy")) {
            count += hookBeautyPrivacy(loader)
        }
        if (settings.isOn("security_center_sidebar_line_color")) {
            count += hookSidebarLineColor(settings)
        }
        return count
    }

    private fun hookAppDefaultSort(loader: ClassLoader, sort: Int): Int {
        val fragment = Reflect.loadClass(loader, "com.miui.appmanager.fragment.ManageFragment")
            ?: return missingHook("security_apps/ManageFragment")
        val method = Reflect.findMethods(fragment, "onActivityCreated", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == Bundle::class.java
        } ?: return missingHook("security_apps/ManageFragment#onActivityCreated")
        return if (HookRuntime.hook(method, "security_apps/default_sort") { chain ->
                val args = chain.args.toTypedArray()
                val bundle = args[0] as? Bundle ?: Bundle().also { args[0] = it }
                // 当前宿主只消费这个拼写错误的历史键；没有 current_sort_type。
                bundle.putInt("current_sory_type", sort - 1)
                chain.proceed(args)
            }
        ) 1 else 0
    }

    /**
     * 当前宿主把旧的六参数 `getAppInfo` 收敛成
     * `t(Object, PackageManager, String, int, int): ApplicationInfo`。
     * 只有 `flags=128, userId=0` 这条应用管理列表查询需要清除系统应用位。
     */
    private fun hookAppRestrict(loader: ClassLoader): Int {
        var count = 0
        val utils = Reflect.loadClass(loader, "com.miui.appmanager.AppManageUtils")
        val getAppInfo = utils?.let { clazz ->
            Reflect.findMethods(clazz, "t", 5).singleOrNull {
                it.returnType == ApplicationInfo::class.java &&
                    it.parameterTypes[1] == PackageManager::class.java &&
                    it.parameterTypes[2] == String::class.java &&
                    it.parameterTypes[3] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[4] == Int::class.javaPrimitiveType
            }
        }
        if (getAppInfo != null &&
            HookRuntime.hook(getAppInfo, "security_apps/AppManageUtils#t") { chain ->
                val result = chain.proceed()
                if (chain.args.getOrNull(3) == 128 && chain.args.getOrNull(4) == 0) {
                    (result as? ApplicationInfo)?.let { info ->
                        info.flags = info.flags and ApplicationInfo.FLAG_SYSTEM.inv()
                    }
                }
                result
            }
        ) {
            count++
        }

        val detail = Reflect.loadClass(
            loader,
            "com.miui.networkassistant.ui.fragment.ShowAppDetailFragment",
        )
        val initFirewall = detail?.let { clazz ->
            Reflect.findMethods(clazz, "initFirewallData", 0).singleOrNull {
                it.returnType == Void.TYPE
            }
        }
        val appInfo = Reflect.loadClass(loader, "com.miui.networkassistant.model.AppInfo")
        val systemField = appInfo?.let { Reflect.findField(it, "isSystemApp") }
            ?.takeIf { it.type == Boolean::class.javaPrimitiveType }
        if (initFirewall != null && systemField != null &&
            HookRuntime.hook(initFirewall, "security_apps/ShowAppDetailFragment#initFirewallData") {
                chain ->
                val info = Reflect.readField(chain.thisObject, "mAppInfo")
                if (info != null) UnsafeAccess.putBooleanField(systemField, info, false)
                chain.proceed()
            }
        ) {
            count++
        }

        val firewall = Reflect.loadClass(
            loader,
            "com.miui.networkassistant.service.FirewallService",
        )
        val rule = firewall?.let { clazz ->
            Reflect.findMethods(clazz, "setSystemAppWifiRuleAllow", 1).singleOrNull {
                it.returnType == Void.TYPE
            }
        }
        if (rule != null &&
            HookRuntime.hook(rule, "security_apps/FirewallService#setSystemAppWifiRuleAllow") {
                null
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_apps/AppRestrict")
        return count
    }

    /**
     * 菜单项由应用详情 Activity 统一承载；停用动作只在用户点击自定义项时执行。
     * 宿主自己的菜单项 6 是举报/卸载类操作之一，开启本功能后仍隐藏它，保持与上游一致。
     */
    private fun hookAppDisable(loader: ClassLoader): Int {
        val activityClass = Reflect.loadClass(
            loader,
            "com.miui.appmanager.ApplicationsDetailsActivity",
        ) ?: return missingHook("security_apps/ApplicationsDetailsActivity")
        var count = 0
        val createMenu = Reflect.findMethods(activityClass, "onCreateOptionsMenu", 1)
            .singleOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return missingHook("security_apps/ApplicationsDetailsActivity#onCreateOptionsMenu")
        if (HookRuntime.hook(createMenu, "security_apps/disable_menu/create") { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                val menu = chain.args.firstOrNull() as? Menu
                if (activity != null && menu != null && menu.findItem(APP_DISABLE_MENU_ID) == null) {
                    val packageName = applicationDetailPackage(activity)
                    val enabled = packageName?.let { isPackageEnabled(activity, it) } ?: true
                    menu.add(0, APP_DISABLE_MENU_ID, 0, appDisableMenuTitle(activity, enabled))
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                result
            }
        ) {
            count++
        }

        val prepareMenu = Reflect.findMethods(activityClass, "onPrepareOptionsMenu", 1)
            .singleOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (prepareMenu != null &&
            HookRuntime.hook(prepareMenu, "security_apps/disable_menu/prepare") { chain ->
                (chain.args.firstOrNull() as? Menu)?.findItem(6)?.isVisible = false
                chain.proceed()
            }
        ) {
            count++
        }

        val selected = Reflect.findMethods(activityClass, "onOptionsItemSelected", 1)
            .singleOrNull { it.returnType == Boolean::class.javaPrimitiveType }
        if (selected != null &&
            HookRuntime.hook(selected, "security_apps/disable_menu/selected") { chain ->
                val item = chain.args.firstOrNull() as? MenuItem
                val activity = chain.thisObject as? Activity
                if (item?.itemId != APP_DISABLE_MENU_ID || activity == null) {
                    chain.proceed()
                } else {
                    handleAppEnabledToggle(activity)
                    true
                }
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_apps/AppDisable")
        return count
    }

    private fun handleAppEnabledToggle(activity: Activity) {
        val packageName = applicationDetailPackage(activity) ?: return
        if (packageName in PROTECTED_APP_PACKAGES) {
            Toast.makeText(activity, "该应用属于关键系统组件，不能在这里停用", Toast.LENGTH_LONG).show()
            return
        }
        val info = Reflect.attempt {
            activity.packageManager.getApplicationInfo(packageName, 0)
        } ?: run {
            Toast.makeText(activity, "无法读取应用信息", Toast.LENGTH_LONG).show()
            return
        }
        val enabled = isPackageEnabled(activity, packageName)
        if (!enabled || (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0) {
            setPackageEnabled(activity, packageName, !enabled)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("停用系统应用")
            .setMessage("停用 $packageName 可能影响系统功能，确定继续吗？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setPackageEnabled(activity, packageName, false)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setPackageEnabled(activity: Activity, packageName: String, enable: Boolean) {
        val expected = if (enable) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val changed = Reflect.attempt {
            activity.packageManager.setApplicationEnabledSetting(packageName, expected, 0)
            true
        } == true
        val applied = changed && isPackageEnabled(activity, packageName) == enable
        Toast.makeText(
            activity,
            if (applied) {
                if (enable) "应用已启用" else "应用已停用"
            } else {
                "修改应用状态失败，系统可能拒绝此操作"
            },
            if (applied) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
        if (applied) activity.invalidateOptionsMenu()
    }

    private fun isPackageEnabled(context: Context, packageName: String): Boolean {
        val state = Reflect.attempt {
            context.packageManager.getApplicationEnabledSetting(packageName)
        } ?: PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        return state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    private fun applicationDetailPackage(activity: Activity): String? =
        activity.intent?.getStringExtra("package_name")?.takeIf { it.isNotBlank() }

    private fun appDisableMenuTitle(activity: Activity, enabled: Boolean): String {
        val name = if (enabled) "app_manager_disable_text" else "app_manager_enable_text"
        val resId = activity.resources.getIdentifier(name, "string", activity.packageName)
        return if (resId != 0) activity.getString(resId) else if (enabled) "停用应用" else "启用应用"
    }

    /**
     * 复用宿主的 `miuix.preference.TextPreference`，只追加可复制文本和两个跳转项，
     * 不替换原偏好 XML。条目在 `z3` 数据加载完成后补齐，因此不会读取到空 PackageInfo。
     */
    private fun hookAppDetails(loader: ClassLoader): Int {
        val fragmentClass = Reflect.loadClass(
            loader,
            "com.miui.appmanager.fragment.ApplicationsDetailsFragment",
        ) ?: return missingHook("security_apps/ApplicationsDetailsFragment")
        val loadFinished = Reflect.findMethods(fragmentClass, "z3", 2).singleOrNull {
            it.returnType == Void.TYPE
        } ?: return missingHook("security_apps/ApplicationsDetailsFragment#z3")
        val textPreference = Reflect.loadClass(loader, "miuix.preference.TextPreference")
            ?: return missingHook("security_apps/TextPreference")
        var count = 0
        if (HookRuntime.hook(loadFinished, "security_apps/details/preferences") { chain ->
                val result = chain.proceed()
                ensureAppDetailPreferences(loader, textPreference, chain.thisObject)
                result
            }
        ) {
            count++
        }
        val click = Reflect.findMethods(fragmentClass, "onPreferenceClick", 1).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (click != null &&
            HookRuntime.hookAfter(click, "security_apps/details/click") { chain, original ->
                if (handleAppDetailClick(chain.thisObject, chain.args.firstOrNull())) {
                    true
                } else {
                    original
                }
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_apps/AppDetails")
        return count
    }

    private fun ensureAppDetailPreferences(
        loader: ClassLoader,
        textPreferenceClass: Class<*>,
        fragment: Any?,
    ) {
        if (fragment == null) return
        val context = Reflect.callWith(fragment, "getContext") as? Context ?: return
        val screen = Reflect.callWith(fragment, "getPreferenceScreen") ?: return
        val packageInfo = Reflect.readField(fragment, "I") as? PackageInfo ?: return
        val constructor = Reflect.attempt {
            textPreferenceClass.declaredConstructors.firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Context::class.java
            }?.also { it.isAccessible = true }
        }
        val values = appDetailValues(packageInfo)
        for ((index, row) in APP_DETAIL_ROWS.withIndex()) {
            val key = row.first
            val title = row.second
            val existing = Reflect.callWith(fragment, "findPreference", key)
            val preference = existing ?: run {
                val created = constructor?.let { ctor ->
                    Reflect.attempt { ctor.newInstance(context) }
                } ?: return@run null
                Reflect.callWith(created, "setKey", key)
                Reflect.callWith(created, "setTitle", title)
                Reflect.callWith(created, "setOrder", 900 + index)
                Reflect.callWith(created, "setPersistent", false)
                val listenerMethod = Reflect.findMethods(
                    created.javaClass,
                    "setOnPreferenceClickListener",
                    1,
                ).firstOrNull()
                if (listenerMethod != null) {
                    Reflect.attempt {
                        listenerMethod.isAccessible = true
                        listenerMethod.invoke(created, fragment)
                        true
                    }
                }
                val added = Reflect.callWith(screen, "addPreference", created)
                if (added == null) created else created
            }
            if (preference == null) continue
            val value = values[key]
            if (value != null) {
                Reflect.callWith(preference, "setText", value)
            } else {
                Reflect.callWith(preference, "setSummary", "")
            }
            Reflect.callWith(preference, "setVisible", true)
        }
    }

    private fun appDetailValues(packageInfo: PackageInfo): Map<String, String> {
        val app = packageInfo.applicationInfo ?: return emptyMap()
        return linkedMapOf(
            APP_DETAIL_VERSION_CODE to packageInfo.longVersionCode.toString(),
            APP_DETAIL_UID to app.uid.toString(),
            APP_DETAIL_DATA_DIR to app.dataDir.orEmpty(),
            APP_DETAIL_SOURCE_DIR to app.sourceDir.orEmpty(),
            APP_DETAIL_MIN_SDK to app.minSdkVersion.toString(),
            APP_DETAIL_TARGET_SDK to app.targetSdkVersion.toString(),
        )
    }

    private fun handleAppDetailClick(fragment: Any?, preference: Any?): Boolean {
        val key = Reflect.callWith(preference, "getKey") as? String ?: return false
        if (key !in APP_DETAIL_ACTION_KEYS && key !in APP_DETAIL_VALUE_KEYS) return false
        val activity = Reflect.callWith(fragment, "getActivity") as? Activity ?: return false
        val packageInfo = Reflect.readField(fragment ?: return false, "I") as? PackageInfo
            ?: return false
        return when (key) {
            APP_DETAIL_DATA_DIR, APP_DETAIL_SOURCE_DIR -> {
                val path = if (key == APP_DETAIL_DATA_DIR) {
                    packageInfo.applicationInfo?.dataDir
                } else {
                    packageInfo.applicationInfo?.sourceDir
                }.orEmpty()
                if (path.isBlank()) {
                    false
                } else {
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE)
                        as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText(key, path))
                    Toast.makeText(activity, "路径已复制", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            APP_DETAIL_MARKET -> {
                openMarket(activity, packageInfo.packageName)
                true
            }
            APP_DETAIL_LAUNCH -> {
                val launch = Reflect.attempt {
                    activity.packageManager.getLaunchIntentForPackage(packageInfo.packageName)
                }
                if (launch == null) {
                    Toast.makeText(activity, "该应用没有可启动的入口", Toast.LENGTH_SHORT).show()
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    activity.startActivity(launch)
                }
                true
            }
            else -> false
        }
    }

    private fun openMarket(activity: Activity, packageName: String) {
        val market = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("market://details?id=$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (Reflect.attempt { activity.startActivity(market); true } == true) return
        val web = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        Reflect.attempt { activity.startActivity(web); true }
    }

    private fun hookAppDefaultSetting(loader: ClassLoader): Int {
        val fragmentClass = Reflect.loadClass(
            loader,
            "com.miui.appmanager.fragment.ApplicationsDetailsFragment",
        ) ?: return missingHook("security_apps/ApplicationsDetailsFragment")
        val loadFinished = Reflect.findMethods(fragmentClass, "z3", 2).singleOrNull {
            it.returnType == Void.TYPE
        } ?: return missingHook("security_apps/ApplicationsDetailsFragment#z3")
        var count = 0
        if (HookRuntime.hook(loadFinished, "security_apps/open_default/update") { chain ->
                val result = chain.proceed()
                updateOpenByDefaultPreference(chain.thisObject)
                result
            }
        ) {
            count++
        }
        val click = Reflect.findMethods(fragmentClass, "onPreferenceClick", 1).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (click != null &&
            HookRuntime.hook(click, "security_apps/open_default/click") { chain ->
                val preference = chain.args.firstOrNull()
                val key = Reflect.callWith(preference, "getKey") as? String
                if (key != "app_default_pref") return@hook chain.proceed()
                val activity = Reflect.callWith(chain.thisObject, "getActivity") as? Activity
                val packageInfo = Reflect.readField(chain.thisObject, "I") as? PackageInfo
                if (activity == null || packageInfo == null) return@hook chain.proceed()
                val intent = Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    data = Uri.parse("package:${packageInfo.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                }
                if (Reflect.attempt { activity.startActivity(intent); true } == true) true
                else chain.proceed()
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_apps/OpenByDefaultSetting")
        return count
    }

    private fun updateOpenByDefaultPreference(fragment: Any?) {
        if (fragment == null) return
        val preference = Reflect.callWith(fragment, "findPreference", "app_default_pref") ?: return
        val packageInfo = Reflect.readField(fragment, "I") as? PackageInfo ?: return
        val context = Reflect.callWith(fragment, "getContext") as? Context ?: return
        Reflect.callWith(preference, "setTitle", "默认打开")
        val summary = Reflect.attempt {
            val manager = context.getSystemService(DomainVerificationManager::class.java)
            val state = manager?.getDomainVerificationUserState(packageInfo.packageName)
            when {
                state == null -> "管理应用的默认打开方式"
                state.isLinkHandlingAllowed -> "始终允许打开支持的链接"
                else -> "不自动用该应用打开链接"
            }
        } ?: "管理应用的默认打开方式"
        Reflect.callWith(preference, "setSummary", summary)
    }

    private fun hookAospAppInfoEntry(loader: ClassLoader): Int {
        val activityClass = Reflect.loadClass(
            loader,
            "com.miui.appmanager.ApplicationsDetailsActivity",
        ) ?: return missingHook("security_apps/ApplicationsDetailsActivity")
        val createMenu = Reflect.findMethods(activityClass, "onCreateOptionsMenu", 1)
            .singleOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return missingHook("security_apps/app_info_entry#onCreateOptionsMenu")
        val myUserId = Reflect.safe("UserHandle#myUserId") {
            UserHandle::class.java.getDeclaredMethod("myUserId")
                .apply { isAccessible = true }
                .invoke(null) as? Int
        } ?: 0
        return if (HookRuntime.hook(createMenu, "security_apps/app_info_entry") { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                val menu = chain.args.firstOrNull() as? Menu
                val packageName = activity?.let(::applicationDetailPackage)
                if (activity != null && menu != null && packageName != null &&
                    menu.findItem(APP_INFO_MENU_ID) == null
                ) {
                    val user = activity.intent?.getIntExtra(
                        "miui.intent.extra.USER_ID",
                        myUserId,
                    ) ?: myUserId
                    val args = Bundle().apply {
                        putString("package", packageName)
                        putInt("uid", user)
                    }
                    val intent = Intent(Intent.ACTION_MAIN).apply {
                        setClassName("com.android.settings", "com.android.settings.SubSettings")
                        putExtra(
                            ":settings:show_fragment",
                            "com.android.settings.applications.appinfo.AppInfoDashboardFragment",
                        )
                        putExtra(":settings:show_fragment_title", "应用信息")
                        putExtra(":settings:show_fragment_args", args)
                    }
                    val item = menu.add(0, APP_INFO_MENU_ID, 0, "应用信息")
                    item.setIntent(intent)
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                result
            }
        ) 1 else 0
    }

    private fun hookAospAppManagerEntry(loader: ClassLoader): Int {
        val activityClass = Reflect.loadClass(loader, "com.miui.appmanager.AppManagerMainActivity")
            ?: return missingHook("security_apps/AppManagerMainActivity")
        val createMenu = Reflect.findMethods(activityClass, "onCreateOptionsMenu", 1)
            .singleOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return missingHook("security_apps/app_manager_entry#onCreateOptionsMenu")
        val intent = Intent(Intent.ACTION_MAIN).setClassName(
            "com.android.settings",
            "com.android.settings.applications.ManageApplications",
        )
        return if (HookRuntime.hook(createMenu, "security_apps/app_manager_entry") { chain ->
                val result = chain.proceed()
                val menu = chain.args.firstOrNull() as? Menu
                if (menu != null && menu.findItem(APP_MANAGER_MENU_ID) == null) {
                    val item = menu.add(0, APP_MANAGER_MENU_ID, 0, "系统应用管理")
                    item.setIntent(intent)
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                result
            }
        ) 1 else 0
    }

    /**
     * `ChargeProtectFragment#M0(Context): String` 位于 `Math.abs` 温差计算链中。
     * Hook 只替换最终显示文本，不改变充电保护状态。
     */
    private fun hookBatteryTemperature(loader: ClassLoader): Int {
        val fragment = Reflect.loadClass(
            loader,
            "com.miui.powercenter.nightcharge.ChargeProtectFragment",
        ) ?: return missingHook("security_battery/ChargeProtectFragment")
        val method = Reflect.findMethods(fragment, "M0", 1).singleOrNull {
            it.returnType == String::class.java &&
                it.parameterTypes[0] == Context::class.java
        } ?: return missingHook("security_battery/ChargeProtectFragment#M0")
        @Suppress("DEPRECATION")
        fun readTemperature(context: Context): String {
            val intent = context.registerReceiver(
                null as BroadcastReceiver?,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val tenths = intent?.getIntExtra("temperature", 0) ?: 0
            return "${tenths / 10} ℃"
        }
        return if (HookRuntime.hook(method, "security_battery/temperature") { chain ->
                (chain.args.firstOrNull() as? Context)?.let(::readTemperature) ?: chain.proceed()
            }
        ) 1 else 0
    }

    /**
     * `qg.a#x(int)` 是安全中心写入健康度的入口；`$c#handleMessage` 是夜间充电页
     * 刷新健康度文本的出口。两处都只观察/更新文本，不改写计算结果。
     */
    private fun hookBatteryHealth(loader: ClassLoader): Int {
        var health: Int? = null
        var preference: Any? = null
        var count = 0
        val healthWriter = Reflect.loadClass(loader, "qg.a")
        val write = healthWriter?.let { clazz ->
            Reflect.findMethods(clazz, "x", 1).singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
        }
        if (write != null &&
            HookRuntime.hook(write, "security_battery/health#write") { chain ->
                val result = chain.proceed()
                val value = chain.args.firstOrNull() as? Int
                if (value != null && value in 0..100) {
                    health = value
                    setBatteryHealthText(preference, health)
                }
                result
            }
        ) {
            count++
        }
        val fragment = Reflect.loadClass(
            loader,
            "com.miui.powercenter.nightcharge.ChargeProtectFragment",
        )
        val createPreferences = fragment?.let { clazz ->
            Reflect.findMethods(clazz, "onCreatePreferences", 2).singleOrNull {
                it.returnType == Void.TYPE
            }
        }
        if (createPreferences != null &&
            HookRuntime.hook(createPreferences, "security_battery/health#preferences") { chain ->
                val result = chain.proceed()
                preference = Reflect.callWith(chain.thisObject, "findPreference", "reference_battery_health")
                setBatteryHealthText(preference, health)
                result
            }
        ) {
            count++
        }
        val handler = Reflect.loadClass(
            loader,
            "com.miui.powercenter.nightcharge.ChargeProtectFragment\$c",
        )
        val handle = handler?.let { clazz ->
            Reflect.findMethods(clazz, "handleMessage", 1).singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes[0] == Message::class.java
            }
        }
        if (handle != null &&
            HookRuntime.hook(handle, "security_battery/health#refresh") { chain ->
                val result = chain.proceed()
                setBatteryHealthText(preference, health)
                result
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_battery/health")
        return count
    }

    private fun setBatteryHealthText(preference: Any?, health: Int?) {
        if (preference == null || health == null) return
        Reflect.callWith(preference, "setText", "$health %")
    }

    private fun hookVideoTemperatureLimit(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "ib.m")
            ?: return missingHook("security_sidebar_video/VideoToolboxTempMonitor")
        val method = Reflect.findMethods(clazz, "q", 0).singleOrNull {
            it.returnType == Float::class.javaPrimitiveType
        } ?: return missingHook("security_sidebar_video/VideoToolboxTempMonitor#q")
        return if (HookRuntime.hookReturning(
                method,
                "security_sidebar_video/temperature_limit",
                0.0f,
            )
        ) 1 else 0
    }

    /**
     * 当前宿主的安全分数链位于 `ScoreManager`：
     * - `q(): int` 汇总扣分；
     * - `s(): int` 与 `j(): int` 由满分减去扣分；
     * - `C(): boolean` 判断风险 Map 是否为空；
     * - `c(y4.g): void` 向风险 Map 写入检测项。
     *
     * `MainContentFrame#onClick(View)` 只处理 `btn_action` 与 `content_main`，
     * 上游同样拦截该入口，避免满分状态下继续进入扣分项详情。
     */
    private fun hookSecurityScore(loader: ClassLoader): Int {
        val manager = Reflect.loadClass(loader, "com.miui.securityscan.scanner.ScoreManager")
            ?: return missingHook("security_score/ScoreManager")
        val riskItem = Reflect.loadClass(loader, "y4.g")
            ?: return missingHook("security_score/y4.g")
        val targets = listOf(
            Triple("q", Int::class.javaPrimitiveType, 0),
            Triple("s", Int::class.javaPrimitiveType, 100),
            Triple("j", Int::class.javaPrimitiveType, 100),
            Triple("C", Boolean::class.javaPrimitiveType, true),
        )
        var count = 0
        for ((name, returnType, value) in targets) {
            val method = Reflect.findMethods(manager, name, 0).singleOrNull {
                it.returnType == returnType
            } ?: continue
            if (HookRuntime.hookReturning(method, "security_score/ScoreManager#$name", value)) {
                count++
            }
        }
        val addRisk = Reflect.findMethods(manager, "c", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == riskItem
        }
        if (addRisk != null &&
            HookRuntime.hook(addRisk, "security_score/ScoreManager#c") { null }
        ) {
            count++
        }
        val frame = Reflect.loadClass(loader, "com.miui.securityscan.ui.main.MainContentFrame")
        val click = frame?.let { clazz ->
            Reflect.findMethods(clazz, "onClick", 1).singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes[0] == View::class.java
            }
        }
        if (click != null &&
            HookRuntime.hook(click, "security_score/MainContentFrame#onClick") { null }
        ) {
            count++
        }
        if (count == 0) missingHook("security_score")
        return count
    }

    /**
     * 当前宿主的危险应用通知入口是 `w4.h#i(JSONObject): void`。
     * 方法内部唯一副作用链路为解析 `DangerousInfo`、构建常驻通知并写入
     * `NotificationManager`，因此安装前跳过原方法即可禁止通知，不影响扫描服务。
     */
    private fun hookMaliciousAppNotification(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "w4.h")
            ?: return missingHook("security_notification/w4.h")
        val method = Reflect.findMethods(clazz, "i", 1).singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes[0].name == "org.json.JSONObject"
        } ?: return missingHook("security_notification/w4.h#i")
        return if (HookRuntime.hook(method, "security_notification/w4.h#i") { null }) 1 else 0
    }

    private fun hookGameToast(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "vb.a1")
            ?: return missingHook("security_sidebar_game/GameToastWindowManager")
        var count = 0
        val show = Reflect.findMethods(clazz, "X", 0).singleOrNull { it.returnType == Void.TYPE }
        if (show != null &&
            HookRuntime.hook(show, "security_sidebar_game/toast#show") { null }
        ) {
            count++
        }
        val cancel = Reflect.findMethods(clazz, "L", 4).singleOrNull { it.returnType == Void.TYPE }
        if (cancel != null &&
            HookRuntime.hook(cancel, "security_sidebar_game/toast#cancel") { null }
        ) {
            count++
        }
        if (count == 0) missingHook("security_sidebar_game/GameToast")
        return count
    }

    private fun hookGunService(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "c9.a")
            ?: return missingHook("security_sidebar_game/GunService")
        val method = Reflect.findMethods(clazz, "l", 1).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == String::class.java
        } ?: return missingHook("security_sidebar_game/GunService#l")
        return if (HookRuntime.hookReturning(method, "security_sidebar_game/gun_service", true)) {
            1
        } else {
            0
        }
    }

    private fun hookMacroBlackList(loader: ClassLoader): Int {
        var count = 0
        val settings = Reflect.loadClass(loader, "com.miui.gamebooster.utils.s1")
        val list = settings?.let { clazz ->
            Reflect.findMethods(clazz, "f", 0).singleOrNull {
                ArrayList::class.java.isAssignableFrom(it.returnType)
            }
        }
        if (list != null &&
            HookRuntime.hookReturning(
                list,
                "security_sidebar_game/macro_black_list#list",
                ArrayList<String>(),
            )
        ) {
            count++
        }
        val blocked = settings?.let { clazz ->
            Reflect.findMethods(clazz, "m", 1).singleOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[0] == String::class.java
            }
        }
        if (blocked != null &&
            HookRuntime.hookReturning(
                blocked,
                "security_sidebar_game/macro_black_list#contains",
                false,
            )
        ) {
            count++
        }
        val macro = Reflect.loadClass(loader, "ea.b")
        if (macro != null) {
            for (method in Reflect.declaredMethodsIncludingInherited(macro)) {
                if (method.returnType != Boolean::class.javaPrimitiveType) continue
                val matches = when (method.parameterCount) {
                    2 -> method.parameterTypes[0] == Context::class.java &&
                        method.parameterTypes[1] == String::class.java
                    3 -> method.parameterTypes[0] == Context::class.java &&
                        method.parameterTypes[1] == String::class.java &&
                        method.parameterTypes[2] == Boolean::class.javaPrimitiveType
                    else -> false
                }
                if (matches &&
                    HookRuntime.hookReturning(
                        method,
                        "security_sidebar_game/macro_black_list#${method.name}",
                        true,
                    )
                ) {
                    count++
                }
            }
        }
        if (count == 0) missingHook("security_sidebar_game/MacroBlackList")
        return count
    }

    private fun hookOpenAppConfirmation(loader: ClassLoader): Int {
        val method = Reflect.findMethods(TextView::class.java, "setText", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == CharSequence::class.java
        } ?: return missingHook("security_ui/TextView#setText")
        return if (HookRuntime.hookAfter(method, "security_ui/open_app_confirmation") { chain, original ->
                val textView = chain.thisObject as? TextView
                val accept = textView?.let(::acceptButtonText)
                if (textView != null && accept != null &&
                    chain.args.firstOrNull()?.toString() == accept
                ) {
                    textView.performClick()
                }
                original
            }
        ) 1 else 0
    }

    private val acceptTextCache = WeakHashMap<Resources, String>()

    private fun acceptButtonText(textView: TextView): String? {
        val resources = textView.context.resources
        synchronized(acceptTextCache) {
            acceptTextCache[resources]?.let { return it }
            val id = resources.getIdentifier(
                "button_text_accept",
                "string",
                textView.context.packageName,
            )
            if (id == 0) return null
            return resources.getString(id).also { acceptTextCache[resources] = it }
        }
    }

    private fun hookAccessibilityAutoDisable(loader: ClassLoader): Int {
        var count = 0
        count += hookVoid(loader, "o6.a", "b", "security_privacy/accessibility#o6.a")
        count += hookVoid(
            loader,
            "com.miui.permcenter.y",
            "d",
            "security_privacy/accessibility#permcenter.y",
        )
        if (count == 0) missingHook("security_privacy/accessibility")
        return count
    }

    private fun hookAppLockPinScramble(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.applicationlock.widget.MiuiNumericInputView",
        ) ?: return missingHook("security_privacy/MiuiNumericInputView")
        val constructor = clazz.declaredConstructors.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(Context::class.java, AttributeSet::class.java))
        } ?: return missingHook("security_privacy/MiuiNumericInputView#<init>")
        return if (HookRuntime.hookAfter(constructor, "security_privacy/pin_scramble") { chain, original ->
                (chain.thisObject as? LinearLayout)?.let(::shufflePinKeys)
                original
            }
        ) 1 else 0
    }

    private fun shufflePinKeys(keys: LinearLayout) {
        if (keys.childCount < 4) return
        val rows = ArrayList<ViewGroup>(4)
        for (index in 0 until 4) {
            val row = keys.getChildAt(index) as? ViewGroup ?: return
            if (row.childCount < 3) return
            rows += row
        }
        val bottomLeft = rows[3].getChildAt(0)
        val bottomRight = rows[3].getChildAt(2)
        val randomViews = ArrayList<View>(10)
        for (rowIndex in 0..3) {
            val row = rows[rowIndex]
            for (column in 0..2) {
                if (rowIndex == 3 && (column == 0 || column == 2)) continue
                randomViews += row.getChildAt(column)
            }
        }
        if (randomViews.size != 10) return
        rows.forEach { it.removeAllViews() }
        Collections.shuffle(randomViews)
        var next = 0
        for (rowIndex in 0..3) {
            for (column in 0..2) {
                when {
                    rowIndex == 3 && column == 0 -> rows[rowIndex].addView(bottomLeft)
                    rowIndex == 3 && column == 2 -> rows[rowIndex].addView(bottomRight)
                    else -> rows[rowIndex].addView(randomViews[next++])
                }
            }
        }
    }

    private fun hookCarSickness(loader: ClassLoader): Int {
        var count = 0
        count += hookBooleanParam(
            loader,
            "s6.b",
            "h",
            1,
            "security_maintain/car_sickness#supported",
            true,
        )
        count += hookBooleanParam(
            loader,
            "s6.b",
            "i",
            1,
            "security_maintain/car_sickness#remind",
            true,
        )
        if (count == 0) missingHook("security_maintain/CarSickness")
        return count
    }

    private fun hookScreenUsedTime(loader: ClassLoader): Int {
        var count = 0
        count += hookBooleanParam(
            loader,
            "com.miui.powercenter.legacypowerrank.f",
            "o",
            0,
            "security_battery/screen_time#supported",
            true,
        )
        count += hookBooleanParam(
            loader,
            "com.miui.powercenter.legacypowerrank.f",
            "n",
            0,
            "security_battery/screen_time#disable_n",
            false,
        )
        count += hookBooleanParam(
            loader,
            "com.miui.powercenter.legacypowerrank.f",
            "p",
            0,
            "security_battery/screen_time#disable_p",
            false,
        )
        if (count == 0) missingHook("security_battery/ScreenUsedTime")
        return count
    }

    private fun hookPowerConsumptionRanking(loader: ClassLoader): Int =
        hookBooleanParam(
            loader,
            "le.c",
            "b",
            0,
            "security_battery/power_ranking#miui9_gate",
            false,
        )

    private data class BatteryInfoRows(
        val loader: ClassLoader,
        val context: Context,
        val category: Any,
        var productionDate: Any?,
        var firstUseDate: Any?,
        var designCapacity: Any?,
        var fullCapacity: Any?,
    )

    private val batteryInfoRows = WeakHashMap<Any, BatteryInfoRows>()

    private fun hookMoreBatteryInfo(loader: ClassLoader): Int {
        val fragment = Reflect.loadClass(
            loader,
            "com.miui.powercenter.nightcharge.ChargeProtectFragment",
        ) ?: return missingHook("security_battery/MoreBatteryInfo#fragment")
        val create = Reflect.findMethods(fragment, "onCreatePreferences", 2).singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes[0] == Bundle::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: return missingHook("security_battery/MoreBatteryInfo#onCreatePreferences")
        val manufacturer = Reflect.findMethods(
            Reflect.loadClass(loader, "yh.o") ?: return missingHook("security_battery/yh.o"),
            "e",
            0,
        ).singleOrNull { it.returnType == String::class.java }
            ?: return missingHook("security_battery/yh.o#e")
        val firstUsage = Reflect.findMethods(
            Reflect.loadClass(loader, "yh.o") ?: return missingHook("security_battery/yh.o"),
            "m",
            0,
        ).singleOrNull { it.returnType == String::class.java }
            ?: return missingHook("security_battery/yh.o#m")
        val batteryInfo = Reflect.loadClass(loader, "yh.i")
            ?: return missingHook("security_battery/yh.i")
        val fullCapacity = Reflect.findMethods(batteryInfo, "i", 0).singleOrNull {
            it.returnType == Int::class.javaPrimitiveType
        } ?: return missingHook("security_battery/yh.i#i")
        val designCapacity = Reflect.findMethods(batteryInfo, "j", 0).singleOrNull {
            it.returnType == Int::class.javaPrimitiveType
        } ?: return missingHook("security_battery/yh.i#j")

        var count = 0
        if (HookRuntime.hookAfter(create, "security_battery/more_info#preferences") { chain, original ->
                captureBatteryInfoRows(chain.thisObject, loader)
                refreshBatteryInfoRows(manufacturer, firstUsage, fullCapacity, designCapacity)
                original
            }
        ) {
            count++
        }
        val handler = Reflect.loadClass(
            loader,
            "com.miui.powercenter.nightcharge.ChargeProtectFragment\$c",
        )
        val handle = handler?.let { clazz ->
            Reflect.findMethods(clazz, "handleMessage", 1).singleOrNull {
                it.returnType == Void.TYPE && it.parameterTypes[0] == Message::class.java
            }
        }
        if (handle != null &&
            HookRuntime.hookAfter(handle, "security_battery/more_info#refresh") { _, original ->
                refreshBatteryInfoRows(manufacturer, firstUsage, fullCapacity, designCapacity)
                original
            }
        ) {
            count++
        }
        return count
    }

    private fun captureBatteryInfoRows(fragment: Any?, loader: ClassLoader) {
        if (fragment == null) return
        val context = (Reflect.callWith(fragment, "getContext") as? Context)
            ?: (Reflect.callWith(fragment, "requireContext") as? Context)
            ?: return
        fun preference(key: String): Any? = Reflect.callWith(fragment, "findPreference", key)
        val category = preference("preference_key_category_battery_info") ?: return
        val rows = BatteryInfoRows(
            loader,
            context,
            category,
            preference("reference_production_date"),
            preference("reference_first_use_date"),
            preference("reference_battery_design_capacity"),
            preference("reference_battery_full_capacity"),
        )
        var nextOrder = nextPreferenceOrder(category)
        if (rows.productionDate == null) {
            rows.productionDate = addTextPreference(
                loader,
                context,
                category,
                "reference_production_date",
                "生产日期",
                nextOrder++,
            )
        }
        if (rows.firstUseDate == null) {
            rows.firstUseDate = addTextPreference(
                loader,
                context,
                category,
                "reference_first_use_date",
                "首次使用日期",
                nextOrder++,
            )
        }
        if (rows.designCapacity == null) {
            rows.designCapacity = addTextPreference(
                loader,
                context,
                category,
                "reference_battery_design_capacity",
                "设计容量",
                nextOrder++,
            )
        }
        if (rows.fullCapacity == null) {
            rows.fullCapacity = addTextPreference(
                loader,
                context,
                category,
                "reference_battery_full_capacity",
                "当前满充容量",
                nextOrder,
            )
        }
        batteryInfoRows[fragment] = rows
    }

    private fun nextPreferenceOrder(category: Any): Int {
        val count = Reflect.callWith(category, "getPreferenceCount") as? Int ?: return 1000
        var max = Int.MIN_VALUE
        for (index in 0 until count) {
            val preference = Reflect.callWith(category, "getPreference", index) ?: continue
            val order = Reflect.callWith(preference, "getOrder") as? Int ?: continue
            max = maxOf(max, order)
        }
        return if (max == Int.MIN_VALUE) count + 100 else (max + 1).coerceAtMost(Int.MAX_VALUE - 4)
    }

    private fun addTextPreference(
        loader: ClassLoader,
        context: Context,
        category: Any,
        key: String,
        title: String,
        order: Int,
    ): Any? {
        val clazz = Reflect.loadClass(loader, "miuix.preference.TextPreference") ?: return null
        val constructor = clazz.getDeclaredConstructor(Context::class.java).also {
            it.isAccessible = true
        }
        val preference = Reflect.attempt { constructor.newInstance(context) } ?: return null
        Reflect.callWith(preference, "setKey", key)
        Reflect.callWith(preference, "setTitle", title)
        Reflect.callWith(preference, "setOrder", order)
        Reflect.callWith(preference, "setClickable", false)
        Reflect.callWith(preference, "setTouchAnimationEnable", false)
        val added = Reflect.callWith(category, "addPreference", preference) != null ||
            Reflect.callWith(category, "findPreference", key) === preference
        return preference.takeIf { added }
    }

    private fun refreshBatteryInfoRows(
        manufacturer: Method,
        firstUsage: Method,
        fullCapacity: Method,
        designCapacity: Method,
    ) {
        if (batteryInfoRows.isEmpty()) return
        val production = staticValue(manufacturer)?.toString()
        val firstUse = staticValue(firstUsage)?.toString()
        val full = (staticValue(fullCapacity) as? Number)?.toInt() ?: 0
        val design = (staticValue(designCapacity) as? Number)?.toInt() ?: 0
        for (rows in batteryInfoRows.values.toList()) {
            setBatteryPreferenceText(rows.productionDate, formatBatteryDate(production))
            setBatteryPreferenceText(rows.firstUseDate, formatBatteryDate(firstUse))
            setBatteryPreferenceText(rows.designCapacity, formatBatteryCapacity(design))
            setBatteryPreferenceText(rows.fullCapacity, formatBatteryCapacity(full))
        }
    }

    private fun staticValue(method: Method): Any? = Reflect.attempt {
        method.isAccessible = true
        method.invoke(null)
    }

    private fun setBatteryPreferenceText(preference: Any?, text: String?) {
        if (preference == null) return
        Reflect.callWith(preference, "setVisible", text != null)
        if (text != null) Reflect.callWith(preference, "setText", text)
    }

    private fun formatBatteryDate(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (!value.matches(Regex("[0-9]{8}")) ||
            value == "00000000" ||
            value == "99999999"
        ) {
            return null
        }
        return Reflect.attempt {
            val parser = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
            val date: Date = parser.parse(value) ?: return@attempt null
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "yMMM")
            SimpleDateFormat(pattern, Locale.getDefault()).format(date)
        }
    }

    private fun formatBatteryCapacity(value: Int): String =
        if (value > 0) "${value / 1000} mAh" else "--"

    private fun hookBubbleSettingsRestriction(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.bubbles.settings.BubblesSettings")
            ?: return missingHook("security_bubbles/BubblesSettings")
        val method = Reflect.findMethods(clazz, "getDefaultBubbles", 0).singleOrNull {
            ArrayMap::class.java.isAssignableFrom(it.returnType)
        } ?: return missingHook("security_bubbles/getDefaultBubbles")
        val bubbleApp = Reflect.loadClass(loader, "com.miui.bubbles.settings.BubbleApp")
            ?: return missingHook("security_bubbles/BubbleApp")
        return if (HookRuntime.hook(method, "security_bubbles/default_apps") { chain ->
                val freeform = freeformSuggestionList(chain.thisObject, loader)
                if (freeform.isEmpty()) return@hook chain.proceed()
                val constructor = Reflect.attempt {
                    bubbleApp.getDeclaredConstructor(
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    ).also { it.isAccessible = true }
                } ?: return@hook chain.proceed()
                val setChecked = Reflect.attempt {
                    bubbleApp.getMethod("setChecked", Boolean::class.javaPrimitiveType)
                } ?: return@hook chain.proceed()
                val userId = (Reflect.readField(chain.thisObject, "mCurrentUserId") as? Int) ?: 0
                val result = ArrayMap<String, Any>()
                for (pkg in freeform) {
                    val app = Reflect.attempt {
                        constructor.newInstance(pkg, userId)
                    } ?: continue
                    Reflect.attempt { setChecked.invoke(app, true) }
                    result[pkg] = app
                }
                result.takeIf { it.isNotEmpty() } ?: chain.proceed()
            }
        ) 1 else 0
    }

    private fun freeformSuggestionList(settings: Any?, loader: ClassLoader): List<String> {
        val context = Reflect.readField(settings ?: return emptyList(), "mContext") as? Context
            ?: return emptyList()
        val utils = Reflect.loadClass(loader, "android.util.MiuiMultiWindowUtils")
            ?: return emptyList()
        val method = Reflect.findMethods(utils, "getFreeformSuggestionList", 1)
            .singleOrNull {
                Modifier.isStatic(it.modifiers) &&
                    it.returnType == List::class.java &&
                    it.parameterTypes[0] == Context::class.java
            } ?: return emptyList()
        val values = Reflect.attempt {
            method.isAccessible = true
            method.invoke(null, context) as? List<*>
        } ?: return emptyList()
        return values.filterIsInstance<String>().distinct()
    }

    private fun hookSideHideFreeform(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.bubbles.settings.BubblesSettings")
            ?: return missingHook("security_bubbles/BubblesSettings")
        var count = 0
        val belongs = Reflect.findMethods(clazz, "isSbnBelongToActiveBubbleApp", 2)
            .singleOrNull {
                it.returnType == Boolean::class.javaPrimitiveType &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
            }
        if (belongs != null &&
            HookRuntime.hookReturning(belongs, "security_bubbles/side_hide#belongs", true)
        ) {
            count++
        }
        val appString = Reflect.findMethods(clazz, "getBubbleAppString", 0).singleOrNull {
            it.returnType == String::class.java
        }
        if (appString != null &&
            HookRuntime.hook(appString, "security_bubbles/side_hide#app_string") { chain ->
                val bubbles = Reflect.readField(chain.thisObject, "mActiveBubbles") as? Set<*>
                if (bubbles == null) return@hook chain.proceed()
                buildString {
                    for (item in bubbles) {
                        val bubble = item ?: continue
                        val pkg = Reflect.callWith(bubble, "getPackageName") as? String ?: continue
                        val userId = Reflect.readField(bubble, "userId") as? Int ?: 0
                        append(pkg).append(':').append(userId).append(',')
                    }
                }
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_bubbles/side_hide_freeform")
        return count
    }

    private fun hookBeautyPrivacy(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "q8.i")
            ?: return missingHook("security_beauty/q8.i")
        var count = 0
        val setPrivacy = Reflect.findMethods(clazz, "Y0", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (setPrivacy != null &&
            HookRuntime.hook(setPrivacy, "security_beauty/privacy#write") { chain ->
                val args = chain.args.toTypedArray()
                args[0] = true
                chain.proceed(args)
            }
        ) {
            count++
        }
        val supported = Reflect.findMethods(clazz, "U", 0).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (supported != null &&
            HookRuntime.hookReturning(supported, "security_beauty/privacy#supported", true)
        ) {
            count++
        }
        if (count == 0) missingHook("security_beauty/privacy")
        return count
    }

    /**
     * 「自动切换补光」与「解锁前置摄像助手」不是同一个门禁。
     *
     * 当前宿主的 `BeautySettingsFragment#onCreatePreferences` 仅在
     * `com.miui.gamebooster.utils.r#d()` 为 true（taoyao）时保留
     * `preference_key_beauty_auto_light`；这个复选框实际读写的是
     * `q8.i#L()` / `q8.i#q0(boolean)` 的 `sp_auto_change_brightness`。
     * `q8.i#M()` 则只负责前置摄像助手的人脸能力分类。
     *
     * 不直接改写 `r#d()`，因为该方法还参与顶部能力图布局与亮度换算。这里只在宿主
     * 完成偏好初始化后，把已被移除的自动补光复选框放回原分类，避免连带改变
     * Face、隐私摄像头或人像居中。
     */
    private fun hookBeautyLightAuto(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.gamebooster.beauty.BeautySettingsFragment",
        ) ?: return missingHook("security_beauty/BeautySettingsFragment")
        val method = Reflect.findMethods(clazz, "onCreatePreferences", 2).singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes[0] == Bundle::class.java &&
                it.parameterTypes[1] == String::class.java
        } ?: return missingHook("security_beauty/BeautySettingsFragment#onCreatePreferences")
        return if (HookRuntime.hookAfter(
            method,
            "security_beauty/light_auto#preferences",
        ) { chain, original ->
                val autoLight = Reflect.readField(chain.thisObject, "f16571c")
                val category = Reflect.readField(chain.thisObject, "f16572d")
                if (autoLight != null && category != null) {
                    val existing = Reflect.callWith(category, "findPreference", BEAUTY_AUTO_LIGHT_KEY)
                    if (existing == null) {
                        Reflect.callWith(category, "addPreference", autoLight)
                    }
                }
                original
            }
        ) 1 else 0
    }

    private fun hookSidebarLineColor(settings: HookSettings): Int {
        val rawDefault = settings.string(SECURITY_CENTER_SIDEBAR_LINE_DEFAULT_KEY)
        val rawDark = settings.string(SECURITY_CENTER_SIDEBAR_LINE_DARK_KEY)
        val rawLight = settings.string(SECURITY_CENTER_SIDEBAR_LINE_LIGHT_KEY)
        val default = SystemUiCustomSettings.color(rawDefault, true)
        val dark = SystemUiCustomSettings.color(rawDark, true)
        val light = SystemUiCustomSettings.color(rawLight, true)
        val replacements = buildMap {
            default?.let { put(SIDEBAR_LINE_DEFAULT_RESOURCE, it) }
            // 上游沿用宿主历史命名：dark 资源取浅色配置，light 资源取深色配置。
            light?.let { put(SIDEBAR_LINE_DARK_RESOURCE, it) }
            dark?.let { put(SIDEBAR_LINE_LIGHT_RESOURCE, it) }
        }
        if (replacements.isEmpty()) {
            ModuleLog.warn("security_sidebar_appearance: no line colors configured")
            return 0
        }
        var count = 0
        val legacy = Reflect.findMethods(Resources::class.java, "getColor", 1).singleOrNull {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (legacy != null &&
            HookRuntime.hook(legacy, "security_sidebar_appearance/color") { chain ->
                val id = chain.args.firstOrNull() as? Int
                if (id == null) chain.proceed() else replacements[id] ?: chain.proceed()
            }
        ) {
            count++
        }
        val themed = Reflect.findMethods(Resources::class.java, "getColor", 2).singleOrNull {
            it.returnType == Int::class.javaPrimitiveType &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Resources.Theme::class.java
        }
        if (themed != null &&
            HookRuntime.hook(themed, "security_sidebar_appearance/themed_color") { chain ->
                val id = chain.args.firstOrNull() as? Int
                if (id == null) chain.proceed() else replacements[id] ?: chain.proceed()
            }
        ) {
            count++
        }
        if (count == 0) missingHook("security_sidebar_appearance/Resources#getColor")
        return count
    }

    private const val SIDEBAR_LINE_DEFAULT_RESOURCE = 0x7f060e99
    private const val SIDEBAR_LINE_DARK_RESOURCE = 0x7f060e9a
    private const val SIDEBAR_LINE_LIGHT_RESOURCE = 0x7f060e9b

    /**
     * `SimLockUtils#m(Context)` 只判断当前是否登录小米账号。上游在当前宿主上
     * 按 `SimLockStartFragment::simLockSetUpFlow` 的调用方定位，最终命中该方法；
     * 不能改同一族的 `i/f/n`，它们分别表示锁屏安全、SIM 可用和设备支持。
     */
    private fun hookSimLockAuth(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.simlock.SimLockUtils")
            ?: return missingHook("security_simlock/SimLockUtils")
        val method = Reflect.findMethods(clazz, "m", 1).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[0] == Context::class.java
        } ?: return missingHook("security_simlock/SimLockUtils#m")
        return if (HookRuntime.hookReturning(
                method,
                "security_simlock/SimLockUtils#m",
                true,
            )
        ) 1 else 0
    }

    /**
     * 成功提示页在 `onCreate()` 末尾直接调用 `e()` 弹框。这里先结束 Activity 并跳过
     * 原生命周期方法，避免对话框短暂出现，也不影响真正的 SIM 卡验证流程。
     */
    private fun hookSimLockSuccessDialog(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.simlock.activity.SuccessDialogActivity",
        ) ?: return missingHook("security_simlock/SuccessDialogActivity")
        val method = Reflect.findMethods(clazz, "onCreate", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == Bundle::class.java
        } ?: return missingHook("security_simlock/SuccessDialogActivity#onCreate")
        return if (HookRuntime.hook(
                method,
                "security_simlock/SuccessDialogActivity#onCreate",
            ) { chain ->
                (chain.thisObject as? Activity)?.finish()
                null
            }
        ) 1 else 0
    }

    /**
     * 两个批量写入入口都会重写隐私预览图名单。设置页自己调整时必须在调用栈里放行，
     * 其余调用（自启、云控同步等）直接跳过。
     */
    private fun hookThumbnailBlur(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.permcenter.privacyblur.ThumbnailBlurUtils",
        ) ?: return missingHook("security_privacy/ThumbnailBlurUtils")
        var count = 0
        for (methodName in listOf("d", "e")) {
            val method = Reflect.findMethods(clazz, methodName, 3).singleOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes[0] == Context::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == Boolean::class.javaPrimitiveType
            } ?: continue
            val installed = HookRuntime.hook(
                method,
                "security_privacy/ThumbnailBlurUtils#$methodName",
            ) { chain ->
                if (Thread.currentThread().stackTrace.any {
                        it.className == "PrivacyThumbnailBlurSettings"
                    }
                ) {
                    chain.proceed()
                } else {
                    null
                }
            }
            if (installed) count++
        }
        if (count == 0) missingHook("security_privacy/ThumbnailBlurUtils#d,e")
        return count
    }

    private fun hookSimplifyHome(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.common.card.CardViewRvAdapter")
            ?: return missingHook("security_maintain/CardViewRvAdapter")
        val method = Reflect.findMethods(clazz, "addAll", 1).singleOrNull {
            it.returnType == Void.TYPE &&
                List::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return missingHook("security_maintain/CardViewRvAdapter#addAll")
        val removed = setOf(
            "com.miui.common.card.models.FuncListBannerCardModel",
            "com.miui.common.card.models.PopularActionCardModel",
        )
        return if (HookRuntime.hook(
                method,
                "security_maintain/CardViewRvAdapter#addAll",
            ) { chain ->
                val args = chain.args.toTypedArray()
                val models = args.firstOrNull() as? List<*>
                if (models == null) {
                    chain.proceed()
                } else {
                    args[0] = models.filterNot { it != null && it.javaClass.name in removed }
                    chain.proceed(args)
                }
            }
        ) 1 else 0
    }

    /**
     * 挂断广播 `hang_up_enable=false` 会进入省电模式；直接跳过该分支。
     * `hang_up_enable` 是当前宿主用来识别此接收器的独有字符串。
     */
    private fun hookMiaoxiangPowerSaver(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "sh.c")
            ?: return missingHook("security_maintain/HangUpChangeReceiver")
        val method = Reflect.findMethods(clazz, "b", 3).singleOrNull {
            it.returnType == Void.TYPE &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == Intent::class.java &&
                it.parameterTypes[2] == Context::class.java
        } ?: return missingHook("security_maintain/HangUpChangeReceiver#b")
        return if (HookRuntime.hook(
                method,
                "security_maintain/HangUpChangeReceiver#b",
            ) { null }
        ) 1 else 0
    }

    /**
     * Kotlin data class 的 `isOffline` 是构造函数写入的 final 字段。构造前已经无法改参数，
     * 因此在构造后和 getter 两处同时固定为 false，覆盖直接读字段与调用 getter 的路径。
     */
    private fun hookOfflineInfo(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.networkassistant.ui.bean.OffLineData\$BaseData",
        ) ?: return missingHook("security_maintain/OffLineData\$BaseData")
        val field = Reflect.findField(clazz, "isOffline")
            ?: return missingHook("security_maintain/OffLineData\$BaseData#isOffline")
        var count = 0
        for (constructor in clazz.declaredConstructors) {
            val installed = HookRuntime.hook(
                constructor,
                "security_maintain/OffLineData\$BaseData#<init>",
            ) { chain ->
                chain.proceed().also { instance ->
                    if (instance != null) {
                        UnsafeAccess.putBooleanField(field, instance, false)
                    }
                }
            }
            if (installed) count++
        }
        val getter = Reflect.findMethods(clazz, "isOffline", 0).singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType
        }
        if (getter != null &&
            HookRuntime.hookReturning(
                getter,
                "security_maintain/OffLineData\$BaseData#isOffline",
                false,
            )
        ) {
            count++
        }
        if (count == 0) missingHook("security_maintain/OffLineData\$BaseData")
        return count
    }

    /**
     * 安全中心通过 `Settings.System` 读取低电量对话框开关与提示音。
     * 只改这两个键，其他系统设置读取保持原样。
     */
    private fun hookLowBatteryWarning(loader: ClassLoader): Int {
        val settingsSystem = Reflect.loadClass(loader, "android.provider.Settings\$System")
            ?: return missingHook("security_maintain/Settings\$System")
        var count = 0
        val getIntMethods = Reflect.findMethods(settingsSystem, "getInt").filter {
            it.returnType == Int::class.javaPrimitiveType && it.parameterCount >= 2 &&
                it.parameterTypes[1] == String::class.java
        }
        for (method in getIntMethods) {
            val installed = HookRuntime.hook(
                method,
                "security_maintain/Settings\$System#getInt",
            ) { chain ->
                if (chain.args.getOrNull(1) == "low_battery_dialog_disabled") {
                    1
                } else {
                    chain.proceed()
                }
            }
            if (installed) count++
        }
        val getStringMethods = Reflect.findMethods(settingsSystem, "getString").filter {
            it.returnType == String::class.java && it.parameterCount >= 2 &&
                it.parameterTypes[1] == String::class.java
        }
        for (method in getStringMethods) {
            val installed = HookRuntime.hook(
                method,
                "security_maintain/Settings\$System#getString",
            ) { chain ->
                if (chain.args.getOrNull(1) == "low_battery_sound") {
                    null
                } else {
                    chain.proceed()
                }
            }
            if (installed) count++
        }
        if (count == 0) missingHook("security_maintain/low_battery_settings")
        return count
    }

    /**
     * 当前宿主把 `persist.vendor.smartchg` 的位掩码分散在多个工具类里。
     * 这些方法覆盖智能充电入口、充电保护、旁路充电和游戏加速判断。
     */
    private fun hookSmartCharge(loader: ClassLoader): Int {
        var count = 0
        count += hookBoolean(loader, "yh.c0", "H", "security_battery/smart_charge#c0.H", true)
        count += hookBoolean(loader, "yh.p", "b", "security_battery/smart_charge#p.b", true)
        count += hookBooleanParam(
            loader,
            "yh.u",
            "c",
            1,
            "security_battery/smart_charge#u.c",
            true,
        )
        count += hookBooleanParam(
            loader,
            "yh.u",
            "d",
            1,
            "security_battery/smart_charge#u.d",
            true,
        )
        count += hookBoolean(
            loader,
            "com.miui.powercenter.charge.protect.e",
            "h",
            "security_battery/smart_charge#protect.h",
            true,
        )
        count += hookBoolean(
            loader,
            "com.miui.powercenter.charge.protect.e",
            "k",
            "security_battery/smart_charge#protect.k",
            true,
        )
        count += hookBoolean(
            loader,
            "com.miui.gamebooster.utils.o0",
            "X",
            "security_battery/smart_charge#gamebooster.X",
            true,
        )
        if (count == 0) missingHook("security_battery/smart_charge")
        return count
    }

    /**
     * `wh.a#c()` 是超级无线充电能力判断，`powercenter.l#T1(boolean)` 把连接状态
     * 写入设置。两处同时处理才能既显示入口，又让状态判断为已连接。
     */
    private fun hookSuperWirelessCharge(loader: ClassLoader): Int {
        var count = 0
        count += hookBoolean(
            loader,
            "wh.a",
            "c",
            "security_battery/super_wireless#a.c",
            true,
        )
        val powerCenter = Reflect.loadClass(loader, "com.miui.powercenter.l")
        if (powerCenter != null) {
            val method = Reflect.findMethods(powerCenter, "T1", 1).singleOrNull {
                it.returnType == Void.TYPE &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            if (method != null &&
                HookRuntime.hook(
                    method,
                    "security_battery/super_wireless#powercenter.T1",
                ) { chain ->
                    val args = chain.args.toTypedArray()
                    args[0] = true
                    chain.proceed(args)
                }
            ) {
                count++
            }
        }
        if (count == 0) missingHook("security_battery/super_wireless")
        return count
    }

    private fun hookSkipCountdown(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.permcenter.privacymanager.model.InterceptBaseActivity",
        ) ?: return missingHook("security_install/InterceptBaseActivity")
        val method = Reflect.findMethods(clazz, "onCreate", 1).singleOrNull {
            it.returnType == Void.TYPE && it.parameterTypes[0] == Bundle::class.java
        } ?: return missingHook("security_install/InterceptBaseActivity#onCreate")
        return if (HookRuntime.hook(
                method,
                "security_install/InterceptBaseActivity#onCreate",
            ) { chain ->
                val args = chain.args.toTypedArray()
                val bundle = args[0] as? Bundle ?: Bundle().also { args[0] = it }
                bundle.putInt("KET_STEP_COUNT", 0)
                bundle.putBoolean("KEY_ALLOW_ENABLE", true)
                chain.proceed(args)
            }
        ) 1 else 0
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

private fun hookBooleanParam(
    loader: ClassLoader,
    className: String,
    methodName: String,
    paramCount: Int,
    label: String,
    value: Boolean,
): Int {
    val clazz = Reflect.loadClass(loader, className) ?: return missingHook(label)
    val method = Reflect.findMethods(clazz, methodName, paramCount)
        .singleOrNull {
            it.returnType == Boolean::class.javaPrimitiveType ||
                it.returnType == Boolean::class.javaObjectType
        }
        ?: return missingHook(label)
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
