/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_CLIPBOARD_PACKAGES
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess
import java.lang.reflect.Method

/** 底层安装、包管理、日志、剪贴板与兼容性功能。 */
internal object SystemFrameworkUnderlyingHooks {

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_disable_miui_watermark")) {
            count += installVoidNoOp(
                loader,
                "com.android.server.wm.MIUIWatermark",
                "drawIfNeeded",
                "miui_watermark",
            )
        }
        if (settings.isOn("system_framework_speed_install")) count += installSpeedInstall(loader)
        if (settings.isOn("system_framework_disable_miui_lite")) count += installMiuiLite(loader)
        if (settings.isOn("system_framework_allow_disable_protected_package")) {
            count += installProtectedPackage(loader)
        }
        if (settings.isOn("system_framework_bypass_unknown_sources")) {
            count += installUnknownSources(loader)
        }
        if (settings.isOn("system_framework_bypass_force_downloadui")) {
            count += installForceDownloadChooser(loader)
        }
        if (settings.isOn("system_framework_bypass_wake_path_checker")) {
            count += installWakePath(loader)
        }
        if (settings.isOn("system_framework_disable_gesture_monitor")) {
            count += installGestureMonitor(loader)
        }
        if (settings.isOn("system_framework_disable_thermal")) count += installThermal(loader)
        if (settings.isOn("system_framework_no_access_device_logs")) {
            count += installLogcatAccess(loader)
        }
        if (settings.isOn("system_framework_posted_clipboard")) {
            count += installPostedClipboard(loader)
        }
        if (settings.isOn("system_framework_disable_cleaner")) count += installDisableCleaner(loader)
        if (settings.isOn("system_framework_bypass_force_mi_app_store") ||
            settings.isOn("system_framework_market_use_detailmini")
        ) {
            count += installMarketIntent(loader, settings)
        }
        if (settings.isOn("system_framework_native_file_picker")) {
            count += installNativeFilePicker(loader)
        }
        if (settings.isOn("system_framework_clipboard_whitelist")) {
            count += installClipboardWhitelist(loader, settings)
        }
        return count
    }

    private fun installVoidNoOp(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
    ): Int {
        val clazz = Reflect.loadClass(loader, className)
        val methods = clazz?.let { Reflect.findMethods(it, methodName) }.orEmpty()
        if (methods.isEmpty()) {
            ModuleLog.warn("$label: $className#$methodName not found")
            return 0
        }
        return methods.count { HookRuntime.hook(it, "$label/$className#$methodName") { null } }
    }

    private fun installSpeedInstall(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "android.content.pm.PackageInstaller")
        val methods = clazz?.let { Reflect.findMethods(it, "isSupportedSpeedInstallV2") }.orEmpty()
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
        if (methods.isEmpty()) {
            ModuleLog.warn("speed_install: PackageInstaller#isSupportedSpeedInstallV2 not found")
            return 0
        }
        return methods.count {
            HookRuntime.hookReturning(it, "speed_install/PackageInstaller#isSupportedSpeedInstallV2", true)
        }
    }

    private fun installMiuiLite(loader: ClassLoader): Int {
        var count = 0
        val build = Reflect.loadClass(loader, "miui.os.Build")
        val method = build?.let { Reflect.findMethods(it, "isMiuiLiteVersion", paramCount = 0).firstOrNull() }
        if (method != null &&
            HookRuntime.hookReturning(method, "miui_lite/miui.os.Build#isMiuiLiteVersion", false)
        ) count++

        val deviceLevel = Reflect.loadClass(loader, "miui.util.DeviceLevel")
        if (deviceLevel != null) {
            for (name in listOf(
                "IS_MIUI_GO_VERSION",
                "IS_MIUI_LITE_VERSION",
                "IS_MIUI_MIDDLE_VERSION",
            )) {
                val field = Reflect.staticFieldOrNull(deviceLevel, name) ?: continue
                if (UnsafeAccess.putStaticBoolean(field, false)) count++
            }
        }
        return count
    }

    private fun installProtectedPackage(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.pm.ProtectedPackages")
        val method = clazz?.let {
            Reflect.findMethods(it, "isPackageStateProtected", paramCount = 2)
                .firstOrNull { candidate -> candidate.returnType == Boolean::class.javaPrimitiveType }
        }
        if (method == null) {
            ModuleLog.warn("protected_package: ProtectedPackages#isPackageStateProtected not found")
            return 0
        }
        return if (HookRuntime.hook(method, "protected_package/ProtectedPackages#isPackageStateProtected") { chain ->
                val fromPm = Thread.currentThread().stackTrace.any {
                    it.className == "com.android.server.pm.PackageManagerService" &&
                        it.methodName == "setEnabledSettings"
                }
                if (fromPm) false else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installUnknownSources(loader: ClassLoader): Int {
        var count = 0
        val appOps = Reflect.loadClass(loader, "com.android.server.appop.AppOpsService")
        if (appOps != null) {
            for (method in Reflect.findMethods(appOps, "noteOperation", paramCount = 7)) {
                if (HookRuntime.hook(method, "unknown_sources/AppOpsService#noteOperation") { chain ->
                        val original = chain.proceed()
                        val op = chain.args.getOrNull(0) as? Int
                        if (op == 66 && original != null) {
                            Reflect.writeField(original, "mOpMode", 0)
                        }
                        original
                    }
                ) count++
            }
        }
        for (className in listOf(
            "com.android.server.pm.IPackageManagerBase",
            "com.android.server.pm.PackageManagerService",
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (method in Reflect.findMethods(clazz, "canRequestPackageInstalls", paramCount = 2)) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(
                        method,
                        "unknown_sources/$className#canRequestPackageInstalls",
                        true,
                    )
                ) count++
            }
        }
        return count
    }

    private fun installForceDownloadChooser(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.ActivityStartController")
        if (clazz == null) {
            ModuleLog.warn("force_download_chooser: ActivityStartController not found")
            return 0
        }
        var count = 0
        for (method in Reflect.findMethods(clazz, "startActivityInPackage")) {
            val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
            if (intentIndex < 0) continue
            if (HookRuntime.hook(method, "force_download_chooser/ActivityStartController#startActivityInPackage") { chain ->
                    val intent = chain.args.getOrNull(intentIndex) as? Intent
                    if (intent == null ||
                        intent.action == Intent.ACTION_CHOOSER ||
                        intent.action != "android.intent.action.VIEW_DOWNLOADS"
                    ) {
                        return@hook chain.proceed()
                    }
                    val replacement = Intent(intent).setPackage(null)
                    val args = chain.args.toTypedArray()
                    args[intentIndex] = Intent.createChooser(replacement, null)
                    chain.proceed(args)
                }
            ) count++
        }
        return count
    }

    private fun installWakePath(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.WakePathChecker")
        if (clazz == null) {
            ModuleLog.warn("wake_path: com.miui.server.WakePathChecker not found")
            return 0
        }
        var count = 0
        for (name in listOf("isAllowedByWakePathRule", "checkAllowStartActivity")) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(method, "wake_path/WakePathChecker#$name", true)
                ) count++
            }
        }
        return count
    }

    private fun installGestureMonitor(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.input.gesture.MiuiGestureMonitor")
        val methods = clazz?.let { Reflect.findMethods(it, "registerPointerEventListener") }.orEmpty()
        if (methods.isEmpty()) {
            ModuleLog.warn("gesture_monitor: MiuiGestureMonitor#registerPointerEventListener not found")
            return 0
        }
        return methods.count { HookRuntime.hook(it, "gesture_monitor/MiuiGestureMonitor#registerPointerEventListener") { null } }
    }

    private fun installThermal(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.power.ThermalManagerService")
        val methods = clazz?.let { Reflect.findMethods(it, "postEventListener") }.orEmpty()
        if (methods.isEmpty()) {
            ModuleLog.warn("disable_thermal: ThermalManagerService#postEventListener not found")
            return 0
        }
        return methods.count { HookRuntime.hook(it, "disable_thermal/ThermalManagerService#postEventListener") { null } }
    }

    private fun installLogcatAccess(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.logcat.LogcatManagerService")
        if (clazz == null) {
            ModuleLog.warn("device_logs: LogcatManagerService not found")
            return 0
        }
        val process = Reflect.findMethods(clazz, "processNewLogAccessRequest", paramCount = 1).firstOrNull()
        val approve = Reflect.findMethods(clazz, "onAccessApprovedForClient", paramCount = 1).firstOrNull()
        if (process == null || approve == null) {
            ModuleLog.warn("device_logs: log access methods not found")
            return 0
        }
        return if (HookRuntime.hook(process, "device_logs/LogcatManagerService#processNewLogAccessRequest") { chain ->
                val accepted = runCatching {
                    approve.isAccessible = true
                    approve.invoke(chain.thisObject, chain.args.getOrNull(0))
                }.isSuccess
                if (accepted) null else chain.proceed()
            }
        ) 1 else 0
    }

    private fun installPostedClipboard(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.clipboard.ClipboardService") ?: return 0
        val methods = Reflect.findMethods(clazz, "lambda\$showAccessNotificationLocked\$5")
            .filter { it.returnType == Void.TYPE && it.parameterCount >= 3 }
        if (methods.isEmpty()) {
            ModuleLog.warn("posted_clipboard: ClipboardService notification callback not found")
            return 0
        }
        return methods.count {
            HookRuntime.hook(it, "posted_clipboard/ClipboardService#showAccessNotificationLocked") { null }
        }
    }

    private fun installDisableCleaner(loader: ClassLoader): Int {
        val voidTargets = mapOf(
            "com.android.server.am.ActivityManagerService" to listOf("checkExcessivePowerUsage"),
            "com.android.server.am.ActivityManagerShellCommand" to listOf("runKillAll"),
            "com.android.server.am.CameraBooster" to listOf("boostCameraIfNeeded"),
            "com.android.server.am.PhantomProcessList" to listOf("trimPhantomProcessesIfNecessary"),
            "com.android.server.am.ProcessMemoryCleaner" to listOf("checkBackgroundProcCompact"),
            "com.android.server.am.ProcessPowerCleaner" to listOf("handleAutoLockOff"),
            "com.android.server.am.SystemPressureController" to listOf("nStartPressureMonitor"),
            "com.android.server.wm.RecentTasks" to listOf("trimInactiveRecentTasks"),
            "com.miui.cameraopt.adapter.ProcessManagerAdapter" to listOf("killApplication"),
        )
        var count = 0
        for ((className, names) in voidTargets) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (name in names) {
                for (method in Reflect.findMethods(clazz, name)) {
                    val value: Any? = when {
                        method.returnType == Void.TYPE -> null
                        method.returnType == Boolean::class.javaPrimitiveType -> false
                        method.returnType == Int::class.javaPrimitiveType -> 0
                        method.returnType == Long::class.javaPrimitiveType -> 0L
                        else -> continue
                    }
                    if (HookRuntime.hook(method, "disable_cleaner/$className#$name") { value }) count++
                }
            }
        }

        val oom = Reflect.loadClass(loader, "com.android.server.am.OomAdjuster")
        if (oom != null) {
            for (method in Reflect.findMethods(oom, "shouldKillExcessiveProcesses")) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(
                        method,
                        "disable_cleaner/OomAdjuster#shouldKillExcessiveProcesses",
                        false,
                    )
                ) count++
            }
            for (method in Reflect.findMethods(oom, "updateAndTrimProcessLSP")) {
                if (method.parameterCount < 3 || method.parameterTypes[2] != Int::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "disable_cleaner/OomAdjuster#updateAndTrimProcessLSP") { chain ->
                        val args = chain.args.toTypedArray()
                        args[2] = 0
                        chain.proceed(args)
                    }
                ) count++
            }
        }
        if (count == 0) ModuleLog.warn("disable_cleaner: no current-host cleaner target found")
        return count
    }

    private fun installMarketIntent(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.ActivityTaskManagerService") ?: return 0
        val forceChooser = settings.isOn("system_framework_bypass_force_mi_app_store")
        val detailMini = settings.isOn("system_framework_market_use_detailmini")
        var count = 0
        for (method in Reflect.findMethods(clazz, "startActivity")) {
            val intentIndex = method.parameterTypes.indexOf(Intent::class.java)
            if (intentIndex < 0) continue
            if (HookRuntime.hook(method, "market_intent/ActivityTaskManagerService#startActivity") { chain ->
                    val original = chain.args.getOrNull(intentIndex) as? Intent
                    if (original == null || original.action != Intent.ACTION_VIEW) {
                        return@hook chain.proceed()
                    }
                    val replacement = rewriteMarketIntent(original, forceChooser, detailMini)
                    if (replacement === original) return@hook chain.proceed()
                    val args = chain.args.toTypedArray()
                    args[intentIndex] = replacement
                    chain.proceed(args)
                }
            ) count++
        }
        return count
    }

    private fun rewriteMarketIntent(intent: Intent, forceChooser: Boolean, detailMini: Boolean): Intent {
        val original = intent.data ?: return intent
        if (original.scheme != "mimarket" && original.scheme != "market") return intent
        var uri = if (original.scheme == "mimarket") {
            original.buildUpon().scheme("market").build()
        } else {
            original
        }
        val path = uri.path.orEmpty()
        if (detailMini && uri.host == "details" && (path.isEmpty() || path == "/")) {
            uri = uri.buildUpon().path("/detailmini").build()
        }
        val isDetails = uri.scheme == "market" &&
            uri.host == "details" &&
            (uri.path.orEmpty().let { it.isEmpty() || it == "/" || it == "/detailmini" }) &&
            uri.getQueryParameter("id") != null
        if (!isDetails) {
            return if (uri == original) intent else applyData(intent, uri)
        }
        val rewritten = Intent(intent).apply {
            setPackage(null)
            removeFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val withData = applyData(rewritten, uri)
        return if (forceChooser) Intent.createChooser(withData, null) else withData
    }

    private fun applyData(intent: Intent, uri: Uri): Intent {
        val type = intent.type
        if (type == null) intent.data = uri else intent.setDataAndType(uri, type)
        return intent
    }

    private fun installNativeFilePicker(loader: ClassLoader): Int {
        var count = 0
        val atm = Reflect.loadClass(loader, "com.android.server.wm.ActivityTaskManagerServiceImpl")
        val refer = atm?.let {
            Reflect.findMethod(it, "mayReferToFileExplore", Intent::class.java, String::class.java)
        }
        if (refer != null &&
            HookRuntime.hook(refer, "native_file_picker/ActivityTaskManagerServiceImpl#mayReferToFileExplore") { chain ->
                chain.args.getOrNull(0)
            }
        ) count++

        val engine = Reflect.loadClass(loader, "com.android.server.pm.ComputerEngine")
        if (engine == null) {
            ModuleLog.warn("native_file_picker: ComputerEngine not found")
            return count
        }
        for (method in Reflect.findMethods(engine, "queryIntentActivitiesInternal")) {
            if (method.parameterCount == 0 || method.parameterTypes[0] != Intent::class.java) continue
            if (HookRuntime.hook(method, "native_file_picker/ComputerEngine#queryIntentActivitiesInternal") { chain ->
                    val original = chain.proceed()
                    @Suppress("UNCHECKED_CAST")
                    val list = original as? MutableList<Any?>
                    if (list != null) {
                        runCatching {
                            list.removeAll { item ->
                                val info = (item as? ResolveInfo)?.activityInfo
                                info?.name == "com.android.photopicker.hyper.HyperMainActivity"
                            }
                        }.onFailure {
                            ModuleLog.warn("native_file_picker: resolved list is not mutable")
                        }
                    }
                    original
                }
            ) count++
        }
        return count
    }

    private fun installClipboardWhitelist(loader: ClassLoader, settings: HookSettings): Int {
        val packages = SystemUiCustomSettings.packages(settings.string(SYSTEM_FRAMEWORK_CLIPBOARD_PACKAGES))
        if (packages.isEmpty()) return 0
        val clazz = Reflect.loadClass(loader, "com.android.server.clipboard.ClipboardService")
        val methods = clazz?.let { Reflect.findMethods(it, "clipboardAccessAllowed") }.orEmpty()
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
        if (methods.isEmpty()) {
            ModuleLog.warn("clipboard_whitelist: ClipboardService#clipboardAccessAllowed not found")
            return 0
        }
        return methods.count { method ->
            HookRuntime.hook(method, "clipboard_whitelist/ClipboardService#clipboardAccessAllowed") { chain ->
                val original = chain.proceed()
                val packageName = chain.args.getOrNull(1) as? String
                if (packageName != null && packageName in packages) true else original
            }
        }
    }
}
