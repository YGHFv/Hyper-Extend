/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.os.Looper
import io.github.YGHFv.HyperExtend.core.CardTileListSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object CardTileListHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val id = CardTileListSettings.FEATURE
        val t = CardTileListTargets
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val owner = Class.forName(t.INITIALIZER, false, loader).getDeclaredField("this\$0").apply {
            require(type.name == t.CONTROLLER && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val constructor = Class.forName(t.CONTROLLER, false, loader).getDeclaredConstructor(
            *t.constructor.map { Class.forName(it, false, loader) }.toTypedArray())
        if (!(methods.filterKeys { it != t.context }.values + constructor)
                .all { HookRuntime.deoptimize(it, "$id/caller") }) return 0
        val ready = AtomicBoolean(false)
        val controllers = WeakHashMap<Any, Boolean>()
        val scope = CardDetailCreationScope()
        var versions: Pair<Long, Long>? = null
        fun main() = Looper.myLooper() == Looper.getMainLooper()
        val init = HookRuntime.hookAfter(methods.getValue(t.initialize), "$id/initialize") { chain, original ->
            if (!ready.get() || SafeModeRuntime.blocked || !main()) return@hookAfter original
            runCatching {
                val controller = owner.get(chain.thisObject) ?: return@runCatching original
                val context = methods.getValue(t.context).invoke(controller) as? Context ?: return@runCatching original
                val host = versions ?: context.packageManager.let { pm ->
                    (pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode to
                        pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode).also { versions = it }
                }
                if (!CardTileListPolicy.eligible(settings.isOn(id), host.first, host.second,
                        context.hostDisplayIdOrNull(), true)) return@runCatching original
                val requested = CardTileListSettings.parse(settings.string(CardTileListSettings.ORDER))
                    ?: return@runCatching original
                // Native lazy stores this once, before the constructor builds exclusions.
                // No dynamic getter substitution can split card/detail/list ownership.
                val selected = Collections.unmodifiableList(ArrayList(requested))
                controllers[controller] = true
                selected
            }.getOrDefault(original)
        }
        val detail = HookRuntime.hook(methods.getValue(t.detail), "$id/detailScope") { chain ->
            if (!ready.get() || !main()) chain.proceed()
            else scope.within { chain.proceed() }
        }
        val create = HookRuntime.hook(methods.getValue(t.create), "$id/detailDeduplicate") { chain ->
            val controller = chain.thisObject
            val spec = chain.args[0] as? String
            if (!ready.get() || !main() || !scope.active || spec == null || controller == null ||
                controllers[controller] != true) return@hook chain.proceed()
            // bt/coolingfan's second native branch must not overwrite the first view/tile.
            if (scope.contains(controller, spec)) return@hook null
            chain.proceed().also { if (it != null) scope.created(controller, spec) }
        }
        ready.set(init && detail && create)
        return if (ready.get()) 3 else 0
    }
}
