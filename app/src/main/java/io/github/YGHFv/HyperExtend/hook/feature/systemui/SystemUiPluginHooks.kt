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

/** Share loader discovery so volume, control-center and shortcut adapters do not compete. */
internal object SystemUiPluginHooks {
    const val PACKAGE = "miui.systemui.plugin"
    private val loaders = Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())

    fun install(loader: ClassLoader, settings: HookSettings): List<String> {
        val appearance = listOf(AppVolumeSettings.FEATURE, "control_center_hide_edit", "volume_hide_collapsed_footer", "volume_default_theme")
            .filter { settings.isOn(it) }
        val shortcuts = LockscreenShortcutPolicy.features.filter { settings.isOn(it) }
        val enabled = appearance + shortcuts
        if (enabled.isEmpty()) return emptyList()
        val factory = Reflect.loadClass(loader, "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")
            ?: return listOf("systemui_plugin=missing_factory")
        val info = factory.getDeclaredField("pluginAppInfo").apply { isAccessible = true }
        var shortcutDiscovery = shortcuts.isNotEmpty()
        fun discover(packageName: String, pluginLoader: ClassLoader) {
            val selected = when (packageName) {
                PACKAGE -> appearance
                LockscreenShortcutPolicy.PACKAGE -> if (shortcutDiscovery) shortcuts else emptyList()
                else -> emptyList()
            }
            if (selected.isEmpty()) return
            synchronized(loaders) { if (!loaders.add(pluginLoader)) return }
            if (packageName == LockscreenShortcutPolicy.PACKAGE) {
                runCatching {
                    val count = LockscreenShortcutHooks.install(pluginLoader, loader, settings)
                    ModuleLog.info("systemui_plugin/shortcuts=$count[${selected.joinToString()}] (runtime acceptance pending)")
                }.onFailure { ModuleLog.error("systemui_plugin/shortcuts unsupported; retaining native behavior", it) }
                return
            }
            for (id in selected) {
                runCatching {
                    val count = when (id) {
                        AppVolumeSettings.FEATURE -> AppVolumeEntryHooks.installPlugin(pluginLoader, settings)
                        else -> PluginAppearanceHooks.install(pluginLoader, id)
                    }
                    ModuleLog.info("systemui_plugin/$id=$count (static targets verified; runtime acceptance pending)")
                }.onFailure { ModuleLog.error("systemui_plugin/$id unsupported; retaining native behavior", it) }
            }
        }
        if (shortcuts.isNotEmpty()) {
            shortcutDiscovery = runCatching {
                val instance = Class.forName("com.android.systemui.shared.plugins.PluginInstance", false, loader)
                val callers = listOf(factory.getDeclaredMethod("createPlugin", instance), factory.getDeclaredMethod("createPluginContext"))
                callers.map { HookRuntime.deoptimize(it, "systemui_plugin/${it.name}") }.all { it }
            }.getOrDefault(false)
            if (!shortcutDiscovery) {
                ModuleLog.warn("systemui_plugin/shortcuts caller deoptimization failed; retaining native shortcuts")
            }
        }
        val hooked = HookRuntime.hookAfter(factory.getDeclaredMethod("createClassLoader"), "systemui_plugin/loader") { chain, result ->
            val packageName = (info.get(chain.thisObject) as? ApplicationInfo)?.packageName
            if (packageName != null && result is ClassLoader) discover(packageName, result)
            result
        }
        Reflect.attempt {
            val injector = Reflect.loadClass(loader, "com.miui.systemui.plugin.PluginInstanceInjector")
            val cache = injector?.getDeclaredField("sClassLoaders")?.apply { isAccessible = true }?.get(null) as? Map<*, *>
            for (packageName in listOf(PACKAGE, LockscreenShortcutPolicy.PACKAGE)) {
                (cache?.get(packageName) as? ClassLoader)?.let { discover(packageName, it) }
            }
        }
        return listOf("systemui_plugin_loader=${if (hooked) 1 else 0}[${enabled.joinToString()}]")
    }
}
