/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.os.Looper
import io.github.YGHFv.HyperExtend.core.NativeSystemUiSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

internal object NativeSystemUiHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = NativeSystemUiTargets
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val constructor = Class.forName(t.VOLUME, false, loader).getDeclaredConstructor(
            *t.volumeConstructor.map { Class.forName(it, false, loader) }.toTypedArray())
        val fields = t.fields.map { (owner, name, expected) ->
            Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
        }
        if (!(methods.values + constructor).all { HookRuntime.deoptimize(it, "native_systemui/caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = NativeExtensionScope()
        var version: Long? = null
        val registration = HookRuntime.hook(methods.getValue(t.register), "native_systemui/pluginSelection") { chain ->
            val plugin = (chain.args[0] as? Class<*>)?.name
            val id = when (plugin) {
                NativeSystemUiPolicy.plugin(NativeSystemUiSettings.VOLUME) -> NativeSystemUiSettings.VOLUME
                NativeSystemUiPolicy.plugin(NativeSystemUiSettings.POWER) -> NativeSystemUiSettings.POWER
                else -> return@hook chain.proceed()
            }
            val eligible = runCatching {
                if (!ready.get() || SafeModeRuntime.blocked || !scope.matches(plugin!!)) return@runCatching false
                val extension = fields[0].get(chain.thisObject) ?: return@runCatching false
                val owner = fields[1].get(extension) ?: return@runCatching false
                val context = fields[2].get(owner) as? Context ?: return@runCatching false
                val hostVersion = version ?: context.packageManager.getPackageInfo("com.android.systemui", 0)
                    .compatibleVersionCode.also { version = it }
                NativeSystemUiPolicy.eligible(settings.isOn(id), hostVersion, context.hostDisplayIdOrNull(),
                    Looper.myLooper() == Looper.getMainLooper())
            }.getOrDefault(false)
            // The original caller still installs its default Supplier, builds the extension,
            // and runs the native init/destroy callbacks. No PM writes or eager instantiation.
            if (eligible) null else chain.proceed()
        }
        val volume = HookRuntime.hook(constructor, "native_systemui/volumeScope") { chain ->
            scope.within(NativeSystemUiSettings.VOLUME) { chain.proceed() }
        }
        val power = HookRuntime.hook(methods.getValue(t.start), "native_systemui/powerScope") { chain ->
            scope.within(NativeSystemUiSettings.POWER) { chain.proceed() }
        }
        ready.set(registration && volume && power)
        return if (ready.get()) 3 else 0
    }
}
