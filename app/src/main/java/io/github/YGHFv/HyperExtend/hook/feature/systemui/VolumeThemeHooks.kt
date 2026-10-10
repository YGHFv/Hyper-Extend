/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.Context
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.concurrent.atomic.AtomicBoolean

internal object VolumeThemeHooks {
    const val FEATURE = "volume_default_theme"
    private const val PREFIX = "com.android.systemui.miui.volume."
    const val RINGER = PREFIX + "MiuiRingerModeLayout\$RingerButtonHelper"
    const val MOTION = PREFIX + "MiuiVolumeDialogMotion"
    val theme = SystemUiMethod("miui.systemui.util.ThemeUtils", "getDefaultPluginTheme", "boolean")
    val ringer = SystemUiMethod(RINGER, "isSuperBlurSupported", "boolean")
    val slider = SystemUiMethod(PREFIX + "VolumeColumnRes", "getSliderBackgroundResId", "int",
        listOf("android.view.View", "boolean", "boolean"), isStatic = true)
    val motion = SystemUiMethod(MOTION, "updateExpandBgState", "void")
    val scopes = listOf(ringer, slider, motion)
    val callers = listOf(
        SystemUiMethod(RINGER, "updateStateWithDefaultMaterial", "void"),
        SystemUiMethod(PREFIX + "VolumeColumn", "setSliderResource", "void", listOf("boolean")),
        SystemUiMethod(MOTION, "updateStateToExpand", "void", listOf("boolean")),
    )
    val methods = listOf(theme) + scopes + callers

    fun result(native: Boolean, scoped: Boolean, enabled: Boolean, ready: Boolean): Boolean =
        native || (scoped && enabled && ready)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val resolved = methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val outer = Class.forName(RINGER, false, loader).getDeclaredField("this\$0").apply {
            require(type.name == PREFIX + "MiuiRingerModeLayout" && !java.lang.reflect.Modifier.isStatic(modifiers))
            isAccessible = true
        }
        val context = Class.forName(MOTION, false, loader).getDeclaredField("mContext").apply {
            require(type == Context::class.java && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }
        if (!resolved.values.map { HookRuntime.deoptimize(it, "$FEATURE/caller") }.all { it }) return 0
        val ready = AtomicBoolean(false)
        val scope = VolumeThemeScope()
        var versions: Boolean? = null
        fun eligible(host: Context?): Boolean = runCatching {
            if (host == null || !ready.get() || !settings.isOn(FEATURE) ||
                Looper.myLooper() != Looper.getMainLooper() || host.hostDisplayIdOrNull() != 0) return@runCatching false
            versions ?: host.packageManager.let { pm ->
                (pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L &&
                    pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode == 183022200L).also { versions = it }
            }
        }.getOrDefault(false)
        val query = HookRuntime.hookAfter(resolved.getValue(theme), "$FEATURE/query") { _, original ->
            result(original as Boolean, scope.active, settings.isOn(FEATURE), ready.get())
        }
        val installed = scopes.map { spec ->
            HookRuntime.hook(resolved.getValue(spec), "$FEATURE/${spec.name}") { chain ->
                val host = runCatching {
                    when (spec) {
                        ringer -> (outer.get(chain.thisObject) as? View)?.context
                        slider -> (chain.args[0] as? View)?.context
                        else -> context.get(chain.thisObject) as? Context
                    }
                }.getOrNull()
                // Excluded nested callers must shadow a previously active volume scope.
                scope.within(eligible(host)) { chain.proceed() }
            }
        }
        ready.set(query && installed.all { it })
        return if (ready.get()) 4 else 0
    }
}

internal class VolumeThemeScope {
    private val state = ThreadLocal<Boolean>()
    val active: Boolean get() = state.get() == true
    fun <T> within(enabled: Boolean, block: () -> T): T {
        val previous = state.get()
        state.set(enabled)
        try { return block() }
        finally { if (previous == null) state.remove() else state.set(previous) }
    }
}
