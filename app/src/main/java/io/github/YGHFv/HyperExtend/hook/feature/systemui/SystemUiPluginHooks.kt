/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.pm.ApplicationInfo
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.TileCornerSettings
import io.github.YGHFv.HyperExtend.core.TileColorSettings
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
        val appearance = (listOf(AppVolumeSettings.FEATURE, "control_center_hide_edit", VolumeFooterHooks.FEATURE, VolumeThemeHooks.FEATURE, StockTilesPolicy.FEATURE, VolumeLongPressPolicy.FEATURE, TileCornerSettings.FEATURE, TileColorSettings.FEATURE) + SliderValuePolicy.features)
            .filter { settings.isOn(it) }
        val shortcuts = LockscreenShortcutPolicy.features.filter { settings.isOn(it) }
        val enabled = appearance + shortcuts
        if (enabled.isEmpty()) return emptyList()
        val factory = Reflect.loadClass(loader, "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")
            ?: return listOf("systemui_plugin=missing_factory")
        val info = factory.getDeclaredField("pluginAppInfo").apply { isAccessible = true }
        var shortcutDiscovery = shortcuts.isNotEmpty()
        var tilesDiscovery = StockTilesPolicy.FEATURE in appearance
        var pressDiscovery = VolumeLongPressPolicy.FEATURE in appearance
        var valueDiscovery = appearance.any { it in SliderValuePolicy.features }
        var editDiscovery = EditButtonHooks.FEATURE in appearance
        val tileAppearance = setOf(TileCornerSettings.FEATURE, TileColorSettings.FEATURE)
        var cornerDiscovery = appearance.any { it in tileAppearance }
        var themeDiscovery = VolumeThemeHooks.FEATURE in appearance
        var footerDiscovery = VolumeFooterHooks.FEATURE in appearance
        fun discover(packageName: String, pluginLoader: ClassLoader) {
            val selected = when (packageName) {
                PACKAGE -> appearance.filter { (it != StockTilesPolicy.FEATURE || tilesDiscovery) &&
                    (it != VolumeLongPressPolicy.FEATURE || pressDiscovery) &&
                    (it !in SliderValuePolicy.features || valueDiscovery) &&
                    (it != EditButtonHooks.FEATURE || editDiscovery) &&
                    (it !in tileAppearance || cornerDiscovery) &&
                    (it != VolumeThemeHooks.FEATURE || themeDiscovery) &&
                    (it != VolumeFooterHooks.FEATURE || footerDiscovery) }
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
            var tileAppearanceInstalled = false
            for (id in selected) {
                if (id in tileAppearance && tileAppearanceInstalled) continue
                if (id in tileAppearance) tileAppearanceInstalled = true
                runCatching {
                    val count = when (id) {
                        AppVolumeSettings.FEATURE -> AppVolumeEntryHooks.installPlugin(pluginLoader, settings)
                        StockTilesPolicy.FEATURE -> StockTilesHooks.installPlugin(pluginLoader, loader, settings)
                        VolumeLongPressPolicy.FEATURE -> VolumeLongPressHooks.install(pluginLoader, settings)
                        in SliderValuePolicy.features -> SliderValueHooks.install(pluginLoader, settings, id)
                        EditButtonHooks.FEATURE -> EditButtonHooks.install(pluginLoader, settings)
                        in tileAppearance -> TileCornerHooks.install(pluginLoader, settings)
                        VolumeThemeHooks.FEATURE -> VolumeThemeHooks.install(pluginLoader, settings)
                        VolumeFooterHooks.FEATURE -> VolumeFooterHooks.install(pluginLoader, settings)
                        else -> 0
                    }
                    val verification = if (id in SliderValuePolicy.features || id in tileAppearance || id in setOf(EditButtonHooks.FEATURE, VolumeThemeHooks.FEATURE, VolumeFooterHooks.FEATURE))
                        "source targets reviewed; runtime acceptance pending"
                    else "static targets verified; runtime acceptance pending"
                    val label = if (id in tileAppearance) selected.filter { it in tileAppearance }.joinToString("+") else id
                    ModuleLog.info("systemui_plugin/$label=$count ($verification)")
                }.onFailure { ModuleLog.error("systemui_plugin/$id unsupported; retaining native behavior", it) }
            }
        }
        if (shortcuts.isNotEmpty() || tilesDiscovery || pressDiscovery || valueDiscovery || editDiscovery || cornerDiscovery || themeDiscovery || footerDiscovery) {
            val discoveryReady = runCatching {
                val instance = Class.forName("com.android.systemui.shared.plugins.PluginInstance", false, loader)
                val callers = listOf(factory.getDeclaredMethod("createPlugin", instance), factory.getDeclaredMethod("createPluginContext"))
                callers.map { HookRuntime.deoptimize(it, "systemui_plugin/${it.name}") }.all { it }
            }.getOrDefault(false)
            shortcutDiscovery = shortcutDiscovery && discoveryReady
            tilesDiscovery = tilesDiscovery && discoveryReady
            pressDiscovery = pressDiscovery && discoveryReady
            valueDiscovery = valueDiscovery && discoveryReady
            editDiscovery = editDiscovery && discoveryReady
            cornerDiscovery = cornerDiscovery && discoveryReady
            themeDiscovery = themeDiscovery && discoveryReady
            footerDiscovery = footerDiscovery && discoveryReady
            if (!discoveryReady) {
                ModuleLog.warn("systemui_plugin caller deoptimization failed; retaining native shortcuts/stock tiles/volume hold/slider values/edit button/tile appearance/volume theme/footer")
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
