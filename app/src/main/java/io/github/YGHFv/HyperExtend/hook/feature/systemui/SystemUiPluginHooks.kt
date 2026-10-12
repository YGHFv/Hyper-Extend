/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.pm.ApplicationInfo
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.AudioMuteSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.TileCornerSettings
import io.github.YGHFv.HyperExtend.core.TileColorSettings
import io.github.YGHFv.HyperExtend.core.TileMaterialSettings
import io.github.YGHFv.HyperExtend.core.CardTileListSettings
import io.github.YGHFv.HyperExtend.core.CardTileColorSettings
import io.github.YGHFv.HyperExtend.core.PercentageSettings
import io.github.YGHFv.HyperExtend.core.LockscreenMaterialSettings
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
        // 「控制中心亮度值 / 音量值」两张卡片已并入 slider_percentage（原生原位数值方式），
        // 所以这里只有 PercentageSettings.FEATURE 一个条目；Style 决定装浮层还是原生原位标签。
        val appearance = listOf(AppVolumeSettings.FEATURE, "control_center_hide_edit", SecurityFooterHooks.FEATURE, VolumeFooterHooks.FEATURE, VolumeThemeHooks.FEATURE, StockTilesPolicy.FEATURE, GmsTilePolicy.FEATURE, TaplusTilePolicy.FEATURE, SunlightTilePolicy.FEATURE, AudioMuteSettings.FEATURE, VolumeLongPressPolicy.FEATURE, TileCornerSettings.FEATURE, TileColorSettings.FEATURE, TileMaterialSettings.FEATURE, CardTileColorSettings.FEATURE, CardTileListSettings.FEATURE, PercentageSettings.FEATURE)
            .filter { settings.isOn(it) }
        val shortcuts = (LockscreenShortcutPolicy.features + LockscreenMaterialSettings.FEATURE).filter { settings.isOn(it) }
        val enabled = appearance + shortcuts
        if (enabled.isEmpty()) return emptyList()
        val factory = Reflect.loadClass(loader, "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory")
            ?: return listOf("systemui_plugin=missing_factory")
        val info = factory.getDeclaredField("pluginAppInfo").apply { isAccessible = true }
        var shortcutDiscovery = shortcuts.isNotEmpty()
        var tilesDiscovery = StockTilesPolicy.FEATURE in appearance || GmsTilePolicy.FEATURE in appearance || TaplusTilePolicy.FEATURE in appearance || SunlightTilePolicy.FEATURE in appearance || AudioMuteSettings.FEATURE in appearance
        var pressDiscovery = VolumeLongPressPolicy.FEATURE in appearance
        var editDiscovery = EditButtonHooks.FEATURE in appearance
        val tileAppearance = setOf(TileCornerSettings.FEATURE, TileColorSettings.FEATURE)
        var cornerDiscovery = appearance.any { it in tileAppearance }
        var themeDiscovery = VolumeThemeHooks.FEATURE in appearance
        var footerDiscovery = VolumeFooterHooks.FEATURE in appearance
        var securityDiscovery = SecurityFooterHooks.FEATURE in appearance
        var cardColorDiscovery = CardTileColorSettings.FEATURE in appearance
        var cardListDiscovery = CardTileListSettings.FEATURE in appearance
        var percentageDiscovery = PercentageSettings.FEATURE in appearance
        var materialDiscovery = TileMaterialSettings.FEATURE in appearance
        fun discover(packageName: String, pluginLoader: ClassLoader) {
            val selected = when (packageName) {
                PACKAGE -> appearance.filter { (it !in setOf(StockTilesPolicy.FEATURE, GmsTilePolicy.FEATURE, TaplusTilePolicy.FEATURE, SunlightTilePolicy.FEATURE, AudioMuteSettings.FEATURE) || tilesDiscovery) &&
                    (it != VolumeLongPressPolicy.FEATURE || pressDiscovery) &&
                    (it != EditButtonHooks.FEATURE || editDiscovery) &&
                    (it !in tileAppearance || cornerDiscovery) &&
                    (it != VolumeThemeHooks.FEATURE || themeDiscovery) &&
                    (it != VolumeFooterHooks.FEATURE || footerDiscovery) &&
                    (it != SecurityFooterHooks.FEATURE || securityDiscovery) &&
                    (it != CardTileColorSettings.FEATURE || cardColorDiscovery) &&
                    (it != CardTileListSettings.FEATURE || cardListDiscovery) &&
                    (it != TileMaterialSettings.FEATURE || materialDiscovery) &&
                    (it != PercentageSettings.FEATURE || percentageDiscovery) }
                LockscreenShortcutPolicy.PACKAGE -> if (shortcutDiscovery) shortcuts else emptyList()
                else -> emptyList()
            }
            if (selected.isEmpty()) return
            synchronized(loaders) { if (!loaders.add(pluginLoader)) return }
            if (packageName == LockscreenShortcutPolicy.PACKAGE) {
                if (selected.any { it in LockscreenShortcutPolicy.features }) runCatching {
                    val count = LockscreenShortcutHooks.install(pluginLoader, loader, settings)
                    ModuleLog.info("systemui_plugin/shortcuts=$count[${selected.joinToString()}] (runtime acceptance pending)")
                }.onFailure { ModuleLog.error("systemui_plugin/shortcuts unsupported; retaining native behavior", it) }
                if (LockscreenMaterialSettings.FEATURE in selected) runCatching {
                    val count = LockscreenMaterialHooks.install(pluginLoader, loader, settings)
                    ModuleLog.info("systemui_plugin/shortcut_material=$count (source only; runtime acceptance pending)")
                }.onFailure { ModuleLog.error("systemui_plugin/shortcut material unsupported; retaining native behavior", it) }
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
                        GmsTilePolicy.FEATURE -> GmsTileEditorHooks.installPlugin(pluginLoader)
                        TaplusTilePolicy.FEATURE -> TaplusTileEditorHooks.installPlugin(pluginLoader)
                        SunlightTilePolicy.FEATURE -> SunlightTileHooks.installPlugin(pluginLoader)
                        AudioMuteSettings.FEATURE -> AudioMuteHooks.installPlugin(pluginLoader)
                        VolumeLongPressPolicy.FEATURE -> VolumeLongPressHooks.install(pluginLoader, settings)
                        EditButtonHooks.FEATURE -> EditButtonHooks.install(pluginLoader, settings)
                        in tileAppearance -> TileCornerHooks.install(pluginLoader, settings)
                        VolumeThemeHooks.FEATURE -> VolumeThemeHooks.install(pluginLoader, settings)
                        VolumeFooterHooks.FEATURE -> VolumeFooterHooks.install(pluginLoader, settings)
                        SecurityFooterHooks.FEATURE -> SecurityFooterHooks.installPlugin(pluginLoader, settings)
                        CardTileColorSettings.FEATURE -> CardTileColorHooks.install(pluginLoader, settings)
                        CardTileListSettings.FEATURE -> CardTileListHooks.install(pluginLoader, settings)
                        PercentageSettings.FEATURE -> PercentageHooks.install(pluginLoader, loader, settings)
                        TileMaterialSettings.FEATURE -> TileMaterialHooks.install(pluginLoader, settings)
                        else -> 0
                    }
                    val verification = if (id in tileAppearance ||
                        id in setOf(EditButtonHooks.FEATURE, VolumeThemeHooks.FEATURE, VolumeFooterHooks.FEATURE,
                            TileMaterialSettings.FEATURE, PercentageSettings.FEATURE, CardTileColorSettings.FEATURE, CardTileListSettings.FEATURE, StockTilesPolicy.FEATURE, GmsTilePolicy.FEATURE, TaplusTilePolicy.FEATURE, SunlightTilePolicy.FEATURE, AudioMuteSettings.FEATURE))
                        "source targets reviewed; runtime acceptance pending"
                    else "static targets verified; runtime acceptance pending"
                    val label = if (id in tileAppearance) selected.filter { it in tileAppearance }.joinToString("+") else id
                    ModuleLog.info("systemui_plugin/$label=$count ($verification)")
                }.onFailure { ModuleLog.error("systemui_plugin/$id unsupported; retaining native behavior", it) }
            }
        }
        if (shortcuts.isNotEmpty() || tilesDiscovery || pressDiscovery || editDiscovery || cornerDiscovery || themeDiscovery || footerDiscovery || securityDiscovery || cardColorDiscovery || cardListDiscovery || percentageDiscovery || materialDiscovery) {
            val discoveryReady = runCatching {
                val instance = Class.forName("com.android.systemui.shared.plugins.PluginInstance", false, loader)
                val callers = listOf(factory.getDeclaredMethod("createPlugin", instance), factory.getDeclaredMethod("createPluginContext"))
                callers.map { HookRuntime.deoptimize(it, "systemui_plugin/${it.name}") }.all { it }
            }.getOrDefault(false)
            shortcutDiscovery = shortcutDiscovery && discoveryReady
            tilesDiscovery = tilesDiscovery && discoveryReady
            pressDiscovery = pressDiscovery && discoveryReady
            editDiscovery = editDiscovery && discoveryReady
            cornerDiscovery = cornerDiscovery && discoveryReady
            themeDiscovery = themeDiscovery && discoveryReady
            footerDiscovery = footerDiscovery && discoveryReady
            securityDiscovery = securityDiscovery && discoveryReady
            cardColorDiscovery = cardColorDiscovery && discoveryReady
            cardListDiscovery = cardListDiscovery && discoveryReady
            percentageDiscovery = percentageDiscovery && discoveryReady
            materialDiscovery = materialDiscovery && discoveryReady
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
