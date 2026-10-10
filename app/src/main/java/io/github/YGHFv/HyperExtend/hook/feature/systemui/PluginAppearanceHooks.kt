/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.feature.volume.AppVolumeEntryHooks
import java.util.WeakHashMap

internal object PluginAppearanceHooks {
    private val footerRequested = WeakHashMap<View, Boolean>()

    fun install(loader: ClassLoader, id: String): Int {
        val suffix = Integer.toHexString(System.identityHashCode(loader))
        if (id == "volume_default_theme") return defaultVolumeTheme(loader, suffix)
        if (id == "control_center_hide_edit") {
            val prefix = "miui.systemui.controlcenter.panel.main."
            val edit = Class.forName(prefix + "qs.EditButtonController", false, loader)
                .getDeclaredMethod("available", Boolean::class.javaPrimitiveType)
            require(edit.returnType == Boolean::class.javaPrimitiveType)
            val distributor = Class.forName(prefix + "MainPanelContentDistributor", false, loader)
                .getDeclaredMethod("distributePanels", Boolean::class.javaPrimitiveType)
            HookRuntime.deoptimize(distributor, "$id/distributePanels")
            return if (HookRuntime.hookReturning(edit, "$id/available/$suffix", false)) 1 else 0
        }
        if (id != "volume_hide_collapsed_footer") return 0
        val dialog = Class.forName("com.android.systemui.miui.volume.MiuiVolumeDialogView", false, loader)
        val footer = dialog.getDeclaredMethod("updateFooterVisibility", Boolean::class.javaPrimitiveType)
        val expand = dialog.getDeclaredMethod("onExpandStateUpdated", Boolean::class.javaPrimitiveType)
        val ringer = dialog.getDeclaredField("mRingerModeLayout").apply { isAccessible = true }
        val controller = Class.forName("com.android.systemui.miui.volume.VolumePanelViewController", false, loader)
        val state = Class.forName("com.android.systemui.plugins.VolumeDialogController\$State", false, loader)
        for (caller in listOf(controller.getDeclaredMethod("onStateChangedH", state), controller.getDeclaredMethod("reInit"),
            controller.getDeclaredMethod("updateExpandedH", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))) {
            HookRuntime.deoptimize(caller, "$id/${caller.name}")
        }
        var count = 0
        if (HookRuntime.hookAfter(footer, "$id/footer/$suffix") { chain, original ->
                val host = chain.thisObject as View
                val requested = chain.args[0] == true
                footerRequested[host] = requested
                val expanded = Reflect.callWith(host, "isExpanded") as? Boolean ?: return@hookAfter original
                val appPanel = AppVolumeEntryHooks.ownsExpandedPanel(host)
                (ringer.get(host) as View).visibility = when {
                    collapsedFooterVisible(requested, expanded, appPanel) -> View.VISIBLE
                    appPanel -> View.INVISIBLE
                    else -> View.GONE
                }
                original
            }) count++
        if (HookRuntime.hookAfter(expand, "$id/expand/$suffix") { chain, original ->
                val host = chain.thisObject as View
                val requested = footerRequested[host]
                if (AppVolumeEntryHooks.ownsExpandedPanel(host)) (ringer.get(host) as View).visibility = View.INVISIBLE
                else if (chain.args[0] == false) (ringer.get(host) as View).visibility = View.GONE
                else if (requested != null) (ringer.get(host) as View).visibility = if (requested) View.VISIBLE else View.GONE
                original
            }) count++
        return count
    }

    private fun defaultVolumeTheme(loader: ClassLoader, suffix: String): Int {
        val theme = Class.forName("miui.systemui.util.ThemeUtils", false, loader)
            .getDeclaredMethod("getDefaultPluginTheme")
        require(theme.returnType == Boolean::class.javaPrimitiveType)
        val callers = listOf(
            Class.forName("com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper", false, loader)
                .getDeclaredMethod("isSuperBlurSupported"),
            Class.forName("com.android.systemui.miui.volume.VolumeColumnRes", false, loader)
                .getDeclaredMethod("getSliderBackgroundResId", View::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
            Class.forName("com.android.systemui.miui.volume.MiuiVolumeDialogMotion", false, loader)
                .getDeclaredMethod("updateExpandBgState"),
        )
        val inVolume = ThreadLocal<Boolean>()
        // ThemeUtils is shared by QS cards and device controls. Never force it globally.
        if (!HookRuntime.hook(theme, "volume_default_theme/query/$suffix") { chain ->
                if (inVolume.get() == true) true else chain.proceed()
            }) return 0
        return 1 + callers.count { method ->
            HookRuntime.deoptimize(method, "volume_default_theme/caller")
            HookRuntime.hook(method, "volume_default_theme/${method.name}/$suffix") { chain ->
                val previous = inVolume.get()
                inVolume.set(true)
                try { chain.proceed() }
                finally { if (previous == null) inVolume.remove() else inVolume.set(previous) }
            }
        }
    }
}

internal fun collapsedFooterVisible(hostRequested: Boolean, expanded: Boolean, appPanel: Boolean = false): Boolean =
    hostRequested && expanded && !appPanel
