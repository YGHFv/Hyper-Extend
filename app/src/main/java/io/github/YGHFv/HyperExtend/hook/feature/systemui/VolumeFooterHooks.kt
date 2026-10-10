/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.volume.AppVolumeEntryHooks
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object VolumeFooterHooks {
    const val FEATURE = "volume_hide_collapsed_footer"
    const val DIALOG = "com.android.systemui.miui.volume.MiuiVolumeDialogView"
    private const val CONTROLLER = "com.android.systemui.miui.volume.VolumePanelViewController"
    val update = SystemUiMethod(DIALOG, "updateFooterVisibility", "void", listOf("boolean"))
    val expand = SystemUiMethod(DIALOG, "onExpandStateUpdated", "void", listOf("boolean"))
    val expanded = SystemUiMethod("com.android.systemui.miui.volume.widget.ExpandCollapseLinearLayout", "isExpanded", "boolean")
    val callers = listOf(
        SystemUiMethod(CONTROLLER, "onStateChangedH", "void", listOf("com.android.systemui.plugins.VolumeDialogController\$State")),
        SystemUiMethod(CONTROLLER, "reInit", "void"),
        SystemUiMethod(CONTROLLER, "updateExpandedH", "void", listOf("boolean", "boolean", "boolean")),
        SystemUiMethod(DIALOG, "showH", "void", listOf("java.lang.Runnable")),
        SystemUiMethod("com.android.systemui.miui.volume.widget.ExpandCollapseStateHelper", "updateExpanded", "void", listOf("boolean", "boolean")),
    )
    val methods = listOf(update, expand, expanded) + callers

    fun suppress(visible: Boolean, expanded: Boolean, enabled: Boolean, appPanel: Boolean): Boolean =
        visible && !expanded && enabled && !appPanel

    fun restoreVisibility(saved: Int?, current: Int, appPanel: Boolean): Int? =
        saved?.takeIf { current == 8 && !appPanel }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val resolved = methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val ringer = Class.forName(DIALOG, false, loader).getDeclaredField("mRingerModeLayout").apply {
            require(type.name == "com.android.systemui.miui.volume.MiuiRingerModeLayout" &&
                !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        val ordinary = Class.forName(DIALOG, false, loader).getDeclaredField("mNeedShowDialog").apply {
            require(type == Boolean::class.javaPrimitiveType && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        if (!resolved.values.map { HookRuntime.deoptimize(it, "$FEATURE/caller") }.all { it }) return 0
        val ready = AtomicBoolean(false)
        val owned = WeakHashMap<View, Int>()
        var versions: Boolean? = null
        fun eligible(view: View): Boolean = runCatching {
            if (!ready.get() || SafeModeRuntime.blocked || !settings.isOn(FEATURE) ||
                !ordinary.getBoolean(view) || (view.display?.displayId ?: view.context.hostDisplayIdOrNull()) != 0) return@runCatching false
            versions ?: view.context.packageManager.let { pm ->
                (pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L &&
                    pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode == 183022200L).also { versions = it }
            }
        }.getOrDefault(false)
        val installed = listOf(update, expand).map { spec ->
            HookRuntime.hook(resolved.getValue(spec), "$FEATURE/${spec.name}") { chain ->
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                if (!ready.get() || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                val footer = ringer.get(view) as? View ?: return@hook chain.proceed()
                val appPanel = AppVolumeEntryHooks.ownsExpandedPanel(view)
                val previous = owned.remove(footer)
                // Restore before native layout/insets processing, never reconstruct visibility
                // from a stale requested flag or override the independent app-volume panel.
                restoreVisibility(previous, footer.visibility, appPanel)?.let { footer.visibility = it }
                val result = chain.proceed()
                val current = ringer.get(view) as? View ?: return@hook result
                if (suppress(current.visibility == View.VISIBLE,
                        resolved.getValue(expanded).invoke(view) != false, eligible(view),
                        AppVolumeEntryHooks.ownsExpandedPanel(view))) {
                    owned[current] = current.visibility
                    current.visibility = View.GONE
                }
                result
            }
        }
        ready.set(installed.all { it })
        return if (ready.get()) 2 else 0
    }
}
