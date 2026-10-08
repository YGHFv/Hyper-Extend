/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.pm.ApplicationInfo
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.feature.volume.AppVolumeEntryHooks
import java.util.Collections
import java.util.WeakHashMap

/** One factory interceptor per module: API 102 rejects independent hooks of this method. */
internal object SystemUiPluginHooks {
    const val PACKAGE = "miui.systemui.plugin"
    private val loaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    fun install(loader: ClassLoader, settings: HookSettings): List<String> {
        val enabled = listOf(AppVolumeSettings.FEATURE, "control_center_hide_edit", "volume_hide_collapsed_footer")
            .filter { settings.isOn(it) }
        if (enabled.isEmpty()) return emptyList()
        val factory = Reflect.loadClass(loader, "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")
            ?: return listOf("systemui_plugin=missing_factory")
        val info = factory.getDeclaredField("pluginAppInfo").apply { isAccessible = true }
        fun discover(pluginLoader: ClassLoader) {
            synchronized(loaders) { if (!loaders.add(pluginLoader)) return }
            for (id in enabled) {
                runCatching {
                    val count = when (id) {
                        AppVolumeSettings.FEATURE -> AppVolumeEntryHooks.installPlugin(pluginLoader)
                        else -> PluginAppearanceHooks.install(pluginLoader, id)
                    }
                    ModuleLog.info("systemui_plugin/$id=$count (static targets verified; runtime acceptance pending)")
                }.onFailure { ModuleLog.error("systemui_plugin/$id unsupported; retaining native behavior", it) }
            }
        }
        val hooked = HookRuntime.hookAfter(factory.getDeclaredMethod("createClassLoader"), "systemui_plugin/loader") { chain, result ->
            if ((info.get(chain.thisObject) as? ApplicationInfo)?.packageName == PACKAGE && result is ClassLoader) discover(result)
            result
        }
        Reflect.attempt {
            val injector = Reflect.loadClass(loader, "com.miui.systemui.plugin.PluginInstanceInjector")
            val cache = injector?.getDeclaredField("sClassLoaders")?.apply { isAccessible = true }?.get(null) as? Map<*, *>
            (cache?.get(PACKAGE) as? ClassLoader)?.let(::discover)
        }
        return listOf("systemui_plugin_loader=${if (hooked) 1 else 0}[${enabled.joinToString()}]")
    }
}
