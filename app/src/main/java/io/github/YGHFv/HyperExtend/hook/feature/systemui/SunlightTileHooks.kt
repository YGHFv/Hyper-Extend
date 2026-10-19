/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.provider.Settings
import io.github.YGHFv.HyperExtend.core.SunlightModeSettings
import io.github.YGHFv.HyperExtend.hook.HookSettings

internal object SunlightTilePolicy {
    const val FEATURE = SunlightModeSettings.FEATURE
    const val SPEC = "hyperextend_sunlight"
    const val KEY = "sunlight_mode"
    const val CAPABILITY = "config_sunlight_mode_available"
    fun decode(value: Int): Boolean? = when (value) { 0 -> false; 1 -> true; else -> null }
}

internal object SunlightTileHooks {
    private val adapter = NativeToggleTileHooks(object : NativeToggleBackend {
        override val feature = SunlightTilePolicy.FEATURE
        override val spec = SunlightTilePolicy.SPEC
        override val label = "Sunlight"
        override val iconName = "ic_qs_sunlight"
        override val settingsComponent: ComponentName? = null
        override fun supported(context: Context): Boolean {
            val user = context.getSystemService(UserManager::class.java) ?: return false
            if (user.hasUserRestriction(UserManager.DISALLOW_CONFIG_BRIGHTNESS) || !Settings.System.canWrite(context)) return false
            // 「系统默认模式」还是原来那条原生阳光模式偏好；只有它能用的机型才显示磁贴。
            // 「最大/自定义亮度」两档改走亮度设置，能力与量程在 SunlightBrightnessHooks 里单独复核。
            val parser = runCatching {
                Class.forName("miui.util.FeatureParser", false, context.classLoader)
                    .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, SunlightTilePolicy.CAPABILITY, false) == true
            }.getOrDefault(false)
            return parser || SunlightBrightnessHooks.supported(context)
        }
        override fun read(context: Context): Boolean? =
            if (SunlightBrightnessHooks.overrides(context)) {
                if (SunlightBrightnessHooks.supported(context)) SunlightBrightnessHooks.active(context) else null
            } else {
                SunlightTilePolicy.decode(Settings.System.getInt(context.contentResolver, SunlightTilePolicy.KEY, 0))
            }
        override fun write(context: Context, enabled: Boolean) {
            if (SunlightBrightnessHooks.overrides(context)) {
                check(SunlightBrightnessHooks.apply(context, enabled))
                return
            }
            // 原生控制器订阅的就是这个偏好；不碰亮度、自动亮度与任何 sysfs。
            check(Settings.System.putInt(context.contentResolver, SunlightTilePolicy.KEY, if (enabled) 1 else 0))
        }
    })
    private val editor = NativeToggleTileEditorHooks(SunlightTilePolicy.FEATURE, SunlightTilePolicy.SPEC) { adapter.ready }
    fun install(loader: ClassLoader) = adapter.install(loader) + editor.installClassic(loader)
    fun installPlugin(loader: ClassLoader) = editor.installPlugin(loader)
    fun installLifecycle(loader: ClassLoader, settings: HookSettings) = SunlightBrightnessHooks.install(loader, settings)
}
