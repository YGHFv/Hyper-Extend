/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.hook

import android.os.Bundle
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.feature.PasskeyFix
import io.github.YGHFv.HyperExtend.hook.feature.RotationLockGuard
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 模块入口。libxposed 会在**每一个**被注入进程里各实例化一次本类
 * （名单见 `META-INF/xposed/java_init.list`），所以生命周期回调必须自己判断
 * 「我现在在哪个进程里」：
 *
 * - system_server → [onSystemServerStarting]（它取代了「首个包加载」阶段）
 * - 普通 App → [onPackageReady]
 *
 * 前三个回调都先调 [HookRuntime.attach]（幂等）：**它是唯一把框架引用交出去的时机**，
 * 漏掉任何一个回调都意味着那个进程里所有 feature 拿不到 module 实例、一个 hook 都装不上。
 *
 * ## 这个类的纪律
 *
 * 任何回调体都**不得抛出异常**。这里抛出去的最坏后果不是「功能不生效」，而是
 * 开机循环（system_server）或状态栏反复重启（SystemUI）——用户只能靠恢复模式救。
 * 所以全篇 `runCatching`，并且每个 feature 的装 hook 过程自己也吞异常。
 *
 */
class HyperXposedEntry : XposedModule() {
    private var systemServerProcess = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        systemServerProcess = param.isSystemServer
        HookRuntime.attach(this)
        runCatching {
            ModuleLog.entry(
                "module loaded: process=${param.processName} systemServer=${param.isSystemServer} " +
                    capabilitySummary(),
            )
        }.onFailure { ModuleLog.error("onModuleLoaded failed", it) }
        if (!param.isSystemServer) {
            // 文件日志在**最早的时刻**接上，不依赖任何后续回调：普通宿主进程名 == 包名，
            // 所以 /data/user/0/<进程名>/files/ 就是它自己的私有目录。这个文件的存在本身就是
            // 「注入发生过」的直接证据；它不在，说明连 onModuleLoaded 都没跑 —— 而不是
            // 「跑了但后面某步失败」（那种情况文件里会有失败记录）。
            runCatching {
                ModuleLog.attachFileSink("/data/user/0/${param.processName}/files/hyperextend.log")
            }.onFailure { ModuleLog.error("attach file sink failed", it) }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        HookRuntime.attach(this)
        runCatching {
            ModuleLog.entry("system_server starting: ${capabilitySummary()}")
            installSystemServerHooks(param.classLoader)
        }.onFailure { ModuleLog.error("system_server hook install aborted", it) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        HookRuntime.attach(this)
        runCatching {
            // system_server 里首个包回调已被 onSystemServerStarting 取代；真走到这里说明该 ROM
            // 走了另一条路径，记一笔但不重复装 hook（PasskeyFix 自己幂等，这里只是省一次无用功）。
            // SystemUI and Settings may also share UID 1000; UID alone is not a process identity.
            if (systemServerProcess) {
                ModuleLog.info("packageReady in system_server: ${param.packageName} (no-op)")
                return
            }
            HookDispatcher.dispatch(
                packageName = param.packageName,
                loader = param.classLoader,
                applicationInfo = param.applicationInfo,
            )
        }.onFailure { ModuleLog.error("onPackageReady failed for ${param.packageName}", it) }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean = false
    private fun installSystemServerHooks(loader: ClassLoader) {
        // 文件日志最先接上：system_server 没有应用私有目录，但它自己的 /data/system 归它管；
        // 这台设备上 logcat 拿不到注入侧日志，/data/system/hyperextend.log 是唯一证据源。
        ModuleLog.attachFileSink("/data/system/hyperextend.log")
        // 逃生门之一：用户/adb 事先写下的标记。
        if (KillSwitch.reportOnce()) {
            ModuleLog.warn("system_server hooks skipped (kill switch engaged)")
            return
        }
        if (!hasCapability(XposedInterface.PROP_CAP_SYSTEM)) {
            // 没有 system 能力时挂 system_server 的 hook 会被框架直接拒绝，
            // 提前退出省掉一串误导性的「hook install failed」日志。
            ModuleLog.warn("framework lacks PROP_CAP_SYSTEM — passkey system-server hooks disabled")
            return
        }
        val settings = HookRuntime.settings()
        if (!settings.isAvailable) {
            ModuleLog.warn("system_server settings unavailable — framework hooks skipped")
            return
        }
        if (SafeModeRuntime.begin("system", null, settings)) return
        if (BootLoopGuard.shouldSkipSystemServerHooks(settings, countRestart = true)) {
            ModuleLog.warn("system_server hooks skipped (framework hook fuse engaged)")
            return
        }
        PasskeyFix.installSystemServer(loader, settings)
        // 旋转锁定保护同样住在 system_server：它守的是「强制停止」这个动作本身，
        // 而 system_server 正是所有 force-stop 的必经之地。
        RotationLockGuard.installDeferred(loader, settings)
    }

    private fun hasCapability(capability: Long): Boolean =
        runCatching { (this as XposedInterface).frameworkProperties and capability != 0L }
            .getOrDefault(false)

    /**
     * 模块自己的类加载器，热重载后拿不到宿主 classloader 时用它兜底。
     *
     * LSPosed 把模块 dex 挂在宿主进程里，模块的类加载器以宿主类加载器为父，
     * 所以 `Class.forName(宿主类名, false, 它)` 照样解析得到宿主类。
     *
     * 这**只是兜底**：正常路径上前面的 `HookRuntime.hostLoader` 一定还在用
     * （它记的是 `PackageReadyParam.getClassLoader()`，也就是宿主自己的那个）。
     * `javaClass.classLoader` 在理论上可能为 null（由 bootstrap 加载），
     * 真到那一步说明这次重载已经没救了，给一个不会 NPE 的替身让流程走完并留下日志。
     */
    private fun moduleClassLoader(): ClassLoader =
        javaClass.classLoader ?: ClassLoader.getSystemClassLoader()

    /**
     * 当前进程名。
     *
     * `HotReloadingParam` 不带进程名（它没有继承 `ModuleLoadedParam`），所以在
     * [onHotReloading] 里只能自己取。读 `/proc/self/cmdline` 是不需要任何权限的、
     * 也不会像 `ActivityThread.currentProcessName()` 那样依赖内部 API。
     */
    private fun currentProcessName(): String = runCatching {
        java.io.File("/proc/self/cmdline").readText().trim().substringBefore('\u0000')
    }.getOrNull().orEmpty()

    /**
     * 能力摘要。
     *
     * `remote` 尤其重要：它为 0 时所有开关都读不到，界面上改了也没用 —— 用户看到的症状是
     * 「开关按了没反应」，而日志里只有一句 `RemotePreferences unavailable`。
     * 把这行打在最前面，能让这个问题一眼可辨。
     */
    private fun capabilitySummary(): String = runCatching {
        val framework = this as XposedInterface
        val props = framework.frameworkProperties
        buildString {
            append("api=").append(framework.apiVersion)
            append(" framework=").append(framework.frameworkName)
            append(' ').append(framework.frameworkVersion)
            append('(').append(framework.frameworkVersionCode).append(')')
            append(" system=").append(props and XposedInterface.PROP_CAP_SYSTEM != 0L)
            append(" remote=").append(props and XposedInterface.PROP_CAP_REMOTE != 0L)
            append(" rtProtection=").append(props and XposedInterface.PROP_RT_API_PROTECTION != 0L)
            append(" | ").append(HostPlatform.describe())
        }
    }.getOrElse { "capability read failed: ${it.javaClass.simpleName}" }
}
