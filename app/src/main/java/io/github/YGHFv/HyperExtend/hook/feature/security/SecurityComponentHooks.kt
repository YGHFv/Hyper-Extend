/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.security

import android.content.Context
import android.net.Uri
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import java.lang.reflect.Method
import java.util.ArrayList

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
        return count
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
