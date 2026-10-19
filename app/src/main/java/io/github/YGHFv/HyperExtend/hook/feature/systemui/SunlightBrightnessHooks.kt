/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SunlightModeSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「原生阳光模式磁贴」里「强制最高亮度 / 自定义亮度」两档的写入侧。
 *
 * 上游把这两档实现成 chmod 777 背光节点后写 `Integer.MAX_VALUE` / 一个裸数值。
 * 真机取证（见 [SunlightModeSettings] 与 docs/remaining5-resolution-2026-10-12.json）：
 * 背光节点是 `system:system u:object_r:vendor_sysfs_graphics:s0`，SystemUI 既不是 system
 * 也不是 root，DAC 与 SELinux 都写不进去；而且上游把「上一个亮度」只放在进程字段里，
 * 节点停在写入值、进程却忘了自己开过 —— 正是我们拒绝照抄的那种不可恢复状态。
 *
 * 这里改走系统自己的亮度设置面（`Settings.System.screen_brightness` / `screen_brightness_mode`）：
 *
 * - 值由 [SunlightModeSettings.targetBrightness] 计算，永远不会超出设备量程；
 * - 写入前把「原亮度 + 原亮度模式」存进 SystemUI 进程自己的 SharedPreferences，
 *   快照跟着宿主活过进程重启与整机重启，因此不存在「进程字段没了、面板值还在」的死局；
 * - 进程启动时对账一次：功能关着（或用户选了系统默认档）就把快照恢复回去，之后不再反复覆盖，
 *   免得和用户手动调的亮度抢同一个键。
 *
 * 只写 `screen_brightness` 与 `screen_brightness_mode` 两个键，不碰 sysfs、不碰
 * `sunlight_mode`、不碰传感器与省电/温控策略。
 */
internal object SunlightBrightnessHooks {
    private const val PREFS = "hyperextend_sunlight"
    private const val KEY_BRIGHTNESS = "snapshot_brightness"
    private const val KEY_MODE = "snapshot_brightness_mode"
    private const val KEY_ACTIVE = "snapshot_active"

    /**
     * 面板接收的是「设置值」，不是 0..1 的归一化亮度；量程由 PowerManager 给出。
     *
     * 这两个 getter 在 framework dex（o51fr44c）里存在，但不是 SDK 公开 API，
     * 所以走反射并逐个复用宿主自己的实现；任何一个拿不到就当作「本设备不支持」。
     */
    private fun range(context: Context): IntRange? = runCatching {
        val power = context.getSystemService(PowerManager::class.java) ?: return@runCatching null
        val minimum = power::class.java.getDeclaredMethod("getMinimumScreenBrightnessSetting")
        val maximum = power::class.java.getDeclaredMethod("getMaximumScreenBrightnessSetting")
        minimum.isAccessible = true
        maximum.isAccessible = true
        (minimum.invoke(power) as Int)..(maximum.invoke(power) as Int)
    }.getOrNull()?.takeIf { !it.isEmpty() }

    /** 本设备是否能用系统亮度设置面实现「最大/自定义亮度」。 */
    fun supported(context: Context): Boolean =
        range(context) != null &&
            runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** 当前设置是否要求「改写亮度设置」而不是切换原生阳光模式偏好。 */
    fun overrides(context: Context): Boolean =
        SunlightModeSettings.overridesBrightness(HookRuntime.settings().string(SunlightModeSettings.MODE))

    private fun target(context: Context, enabled: Boolean): Int? {
        if (!enabled) return null
        val settings = HookRuntime.settings()
        val range = range(context) ?: return null
        val mode = settings.string(SunlightModeSettings.MODE)
        val custom = settings.number(
            SunlightModeSettings.BRIGHTNESS,
            SunlightModeSettings.BRIGHTNESS_DEFAULT,
            SunlightModeSettings.BRIGHTNESS_MIN,
            SunlightModeSettings.BRIGHTNESS_MAX,
        )
        return SunlightModeSettings.targetBrightness(mode, custom, range.first, range.last)
    }

    /** 磁贴状态 = 当前亮度设置是否就是本档要求的值（不另存一个「我以为开着」的布尔）。 */
    fun active(context: Context): Boolean {
        val wanted = target(context, true) ?: return false
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, Int.MIN_VALUE) == wanted
        }.getOrDefault(false)
    }

    /**
     * 一个原生的 setter：[enabled] 为真时先存快照再写目标亮度，为假时按快照恢复。
     *
     * 返回值表示「这次调用是否完成了它该做的事」，供 `NativeToggleTransaction` 判成败；
     * 内部不使用 `check()` 之外的副作用，失败一律抛出由上层记账。
     */
    fun apply(context: Context, enabled: Boolean): Boolean {
        val resolver = context.contentResolver ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (enabled) {
            val wanted = target(context, true) ?: return false
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            // 只有真正要改变时才存快照：否则会把「已经是目标值」的那次读取也存成「原亮度」。
            if (!prefs.getBoolean(KEY_ACTIVE, false) && current != wanted) {
                val mode = runCatching {
                    Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
                }.getOrDefault(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                prefs.edit().putInt(KEY_BRIGHTNESS, current).putInt(KEY_MODE, mode)
                    .putBoolean(KEY_ACTIVE, true).commit()
            }
            // 手动模式：目标亮度只有在非自动模式下才会被直接采用。
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            return Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, wanted)
        }
        return restore(context)
    }

    /**
     * 按快照恢复。没有快照时**什么都不做**并返回 true ——
     * 「没记录过我们改过」这件事本身就是「无需恢复」，不是失败。
     */
    fun restore(context: Context): Boolean {
        val resolver = context.contentResolver ?: return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return true
        return runCatching {
            val brightness = prefs.getInt(KEY_BRIGHTNESS, -1)
            val mode = prefs.getInt(KEY_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            if (brightness >= 0) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            prefs.edit().remove(KEY_BRIGHTNESS).remove(KEY_MODE).remove(KEY_ACTIVE).commit()
        }.isSuccess
    }

    /**
     * 进程启动时对账一次。
     *
     * 只处理两种明确状态：功能关着且我们留下过快照 → 恢复；功能开着且选了会改亮度的档位
     * → 补写一次（覆盖「改配置后还没点过磁贴」的情况）。用户后来手动调的亮度不在启动时被覆盖。
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val instrumentation = Reflect.loadClass(loader, "android.app.Instrumentation") ?: return 0
        val method = instrumentation.declaredMethods.firstOrNull {
            it.name == "callApplicationOnCreate" &&
                it.parameterTypes.size == 1 && Application::class.java.isAssignableFrom(it.parameterTypes[0])
        } ?: return 0
        return if (HookRuntime.hookAfter(method, "${SunlightModeSettings.FEATURE}/brightness-lifecycle") { chain, original ->
                (chain.args.getOrNull(0) as? Application)?.let { app ->
                    runCatching {
                        val on = settings.isOn(SunlightModeSettings.FEATURE)
                        val overrides = SunlightModeSettings.overridesBrightness(settings.string(SunlightModeSettings.MODE))
                        if (!on || !overrides) restore(app)
                        else if (supported(app)) apply(app, true)
                    }.onFailure { ModuleLog.error("${SunlightModeSettings.FEATURE}: brightness reconcile failed", it) }
                }
                original
            }) 1 else 0
    }
}
