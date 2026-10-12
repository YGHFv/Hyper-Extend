/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.NavigationHandleSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「改系统原生隐藏偏好」—— 写系统自带的 `Settings.Global.hide_gesture_line`。
 *
 * 本文件是「手势提示线」卡片里「处理方式 = 改系统原生隐藏偏好」那一档的实现
 * （见 `core/NavigationHandleSettings.MODE_NATIVE`）。合并前它是一张独立卡片
 * `systemui_hide_navigation_bar`，两处写的是同一个全局键，所以合并后只是换了安装入口。
 *
 * ## 为什么是「写全局偏好」而不是「挂方法」
 *
 * 当前 OS4 的横条显隐由系统自己的这个全局偏好决定：
 * `SettingsManager.onHideGestureLineChanged()` 读它、`NavigationModeControllerInjector.mHideGestureLine`
 * 用它、WMShell 的 `MultiTaskingSettingsObserver` 也读它。所以这个功能的正确实现是
 * **把系统本来就在读的那个值改成 1**，而不是再去拦一层绘制（那是另一张卡片 `navigation_handle_custom`
 * 的隐藏方式，两者上游键不同）。
 *
 * ## 为什么能在 SystemUI 里直接写
 *
 * 真机 `dumpsys package com.android.systemui` 已确认它持有 `WRITE_SECURE_SETTINGS`
 * （granted=true）。`Settings.Global` 的读写只需要 `WRITE_SETTINGS` 家族权限，SystemUI 都有，
 * 因此这里既不需要 root，也不需要 `settings put` 子进程。
 *
 * ## 幂等与可恢复
 *
 * 等待 `Instrumentation#callApplicationOnCreate` 拿到宿主 Application 后**做一次对账**：
 * - 卡片开着 → 先把当前 `hide_gesture_line` 的**原值存进宿主自己的 SharedPreferences**（只存一次），
 *   再写 1。快照跟着 SystemUI 活过进程重启与整机重启，所以不存在
 *   「进程忘了自己改过、而这个全局键还停在我们写的值上」的死局。
 * - 卡片关着 → 有快照就写回原值并清掉快照；没有快照就什么都不做
 *   （没快照 = 我们没改过 = 不需要复位，不是失败）。
 *
 * 对账只在进程启动时做一次：用户后来自己拨动了系统的「隐藏手势提示线」开关时，
 * 模块不会在运行期反复覆盖他，避免和用户抢同一个全局键。
 *
 * 合并前那个 `restore_on_disable` 子项**没有被保留**：它的读出条件要求父开关为真，
 * 而这条路径又只在父开关为真时安装 —— 那个开关从来没有生效过（见
 * `core/NavigationHandleSettings.LEGACY_NATIVE_RESTORE` 的说明）。按快照恢复取代了它。
 */
internal object NavigationBarHideHooks {
    private const val PREFS = "hyperextend_native_hide_gesture_line"
    private const val SNAPSHOT = "snapshot"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val instrumentation = Reflect.loadClass(loader, "android.app.Instrumentation") ?: run {
            ModuleLog.warn("${NavigationHandleSettings.FEATURE}/native: Instrumentation not found")
            return 0
        }
        val method = instrumentation.declaredMethods.firstOrNull {
            it.name == "callApplicationOnCreate" &&
                it.parameterTypes.size == 1 && Application::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (method == null) {
            ModuleLog.warn("${NavigationHandleSettings.FEATURE}/native: callApplicationOnCreate not found")
            return 0
        }
        return if (HookRuntime.hookAfter(method, "${NavigationHandleSettings.FEATURE}/native-application") { chain, original ->
                (chain.args.getOrNull(0) as? Application)?.let {
                    reconcile(it, settings)
                }
                original
            }) 1 else 0
    }

    /**
     * 对账一次：把系统偏好设成整张卡片要求的值，并维护可恢复的快照。
     *
     * 只写 `hide_gesture_line` 这一个键；不碰绘制、导航模式、音频或任何 sysfs。
     * 决策本身在 [NavigationHandleSettings.nativeReconcilePlan] 里，这里只负责把它要求的
     * 副作用按顺序落下去：先写值，写成功才记/清快照。写失败只记日志，不影响宿主启动，
     * 已经写下的快照也不会被丢掉（下一次启动会重试）。
     */
    private fun reconcile(app: Context, settings: HookSettings) {
        val resolver = app.contentResolver ?: return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = Settings.Global.getInt(resolver, NavigationHandleSettings.NATIVE_GLOBAL_KEY,
            NavigationHandleSettings.NATIVE_SHOW)
        val snapshot = if (prefs.contains(SNAPSHOT)) {
            prefs.getInt(SNAPSHOT, NavigationHandleSettings.NATIVE_SHOW)
        } else null
        val plan = NavigationHandleSettings.nativeReconcilePlan(
            settings.isOn(NavigationHandleSettings.FEATURE), current, snapshot)
        if (!plan.write) return
        if (!write(app, plan.value)) return
        plan.storeSnapshot?.let { prefs.edit().putInt(SNAPSHOT, it).commit() }
        if (plan.clearSnapshot) prefs.edit().remove(SNAPSHOT).commit()
    }

    /** 写一次全局键；成功返回 true。失败只记日志。 */
    private fun write(app: Context, value: Int): Boolean = runCatching {
        Settings.Global.putInt(app.contentResolver, NavigationHandleSettings.NATIVE_GLOBAL_KEY, value)
    }.onFailure {
        ModuleLog.error("${NavigationHandleSettings.FEATURE}/native: putInt failed", it)
    }.isSuccess
}
