/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_FEATURES
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.feature.RotationLockGuard
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 系统框架功能总安装器。
 *
 * 大部分目标属于 ActivityManager、WindowManager、PackageManager 与 AudioService。
 * 这些类在 `onSystemServerStarting` 阶段还没有进入稳定初始化状态，提前解析它们会使
 * system_server 在启动早期加载整条依赖链，历史上已经造成过开机循环。因此：
 *
 * - 只有 [SystemFrameworkAntiQues] 的外层类加载 Hook 在启动早期安装，它只触碰正在执行的
 *   `SystemServiceManager`；
 * - 其余功能统一挂到 `SystemServer#startOtherServices` 返回之后；
 * - 每个子系统独立 `runCatching`，一个类改名不会阻断其它功能。
 */
internal object SystemFrameworkHooks {
    private const val SYSTEM_SERVER = "com.android.server.SystemServer"
    private const val START_OTHER_SERVICES = "startOtherServices"

    private val deferredArmed = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)

    fun installEarly(loader: ClassLoader, settings: HookSettings) {
        SystemFrameworkAntiQues.install(loader, settings)
    }

    fun installDeferred(loader: ClassLoader, settings: HookSettings) {
        if (!hasDeferredFeature(settings)) return
        if (!deferredArmed.compareAndSet(false, true)) return

        val server = Reflect.loadClass(loader, SYSTEM_SERVER)
        if (server == null) {
            ModuleLog.warn("system_framework: $SYSTEM_SERVER not found; deferred install skipped")
            return
        }
        val anchor = Reflect.findMethods(server, START_OTHER_SERVICES, paramCount = 1).firstOrNull()
        if (anchor == null) {
            ModuleLog.warn("system_framework: $SYSTEM_SERVER#$START_OTHER_SERVICES not found")
            return
        }
        val armed = HookRuntime.hook(anchor, "system_framework/SystemServer#$START_OTHER_SERVICES") { chain ->
            val result = chain.proceed()
            installNow(loader, settings)
            result
        }
        if (armed) {
            ModuleLog.info("system_framework: deferred install armed on $SYSTEM_SERVER#$START_OTHER_SERVICES")
        }
    }

    /**
     * 热路径或已经完成启动时直接安装。只执行一次，避免同一个进程里重复解析和重复挂载。
     */
    fun installNow(loader: ClassLoader, settings: HookSettings) {
        if (!installed.compareAndSet(false, true)) return

        val groups = listOf<Pair<String, () -> Int>>(
            "core_patch" to { SystemFrameworkCorePatchHooks.install(loader, settings) },
            "freeform" to { SystemFrameworkFreeformHooks.install(loader, settings) },
            "volume" to { SystemFrameworkVolumeHooks.install(loader, settings) },
            "display" to { SystemFrameworkDisplayHooks.install(loader, settings) },
            "display_notify" to { SystemFrameworkDisplayNotificationHooks.install(loader, settings) },
            "underlying" to { SystemFrameworkUnderlyingHooks.install(loader, settings) },
            "shortcuts" to { SystemFrameworkShortcutHooks.install(loader, settings) },
            "theme" to { SystemFrameworkThemeHooks.install(loader, settings) },
            "secure" to { SystemFrameworkSecureHooks.install(loader, settings) },
            "gms_doze" to { SystemFrameworkGmsDozeHooks.install(loader, settings) },
            "input" to { SystemFrameworkInputHooks.install(loader, settings) },
            "mipad" to { SystemFrameworkMipadHooks.install(loader, settings) },
            "lock_task" to { SystemLockTaskHooks.installServer(loader, settings) },
            "hook_entry" to { SystemFrameworkHookEntryHooks.install(loader, settings) },
        )
        var total = 0
        for ((name, installer) in groups) {
            total += runCatching(installer).getOrElse {
                ModuleLog.error("system_framework: $name installer failed", it)
                0
            }
        }
        runCatching { RotationLockGuard.installNow(loader, settings) }
            .onFailure { ModuleLog.error("system_framework: rotation lock guard failed", it) }
        ModuleLog.info("system_framework: installed $total hook(s) after system services ready")
    }

    private fun hasDeferredFeature(settings: HookSettings): Boolean =
        settings.isOn("rotation_lock_fix") ||
            SYSTEM_FRAMEWORK_FEATURES.any { feature ->
                feature.id != "system_framework_anti_ques" && settings.isOn(feature.id)
            }
}
