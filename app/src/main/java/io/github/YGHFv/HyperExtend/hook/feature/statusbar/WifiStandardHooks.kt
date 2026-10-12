/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.WIFI_STANDARD_KEY
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ForceBlurTargets
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal object WifiStandardHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("status_bar_icons") || settings.number(WIFI_STANDARD_KEY, 0) != 1) return 0
        val t = WifiStandardTargets
        val emit = NotificationHooks.resolve(loader, t.emit) ?: return 0
        val collect = NotificationHooks.resolve(loader, t.collect) ?: return 0
        val resume = NotificationHooks.resolve(loader, t.resume) ?: return 0
        val delegateEmit = NotificationHooks.resolve(loader, t.delegateEmit) ?: return 0
        val application = NotificationHooks.resolve(loader, ForceBlurTargets.application) ?: return 0
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        val collector = Class.forName(t.COLLECTOR, false, loader)
        if (!collector.isInterface || !listOf(emit, collect, resume).all {
                HookRuntime.deoptimize(it, "status_bar_icons/wifi-standard-caller") }) return 0
        val version = AtomicReference<Long?>(null)
        fun mode(): Int {
            if (SafeModeRuntime.blocked) return 0
            val now = HookRuntime.settings()
            return if (now.isOn("status_bar_icons")) now.number(WIFI_STANDARD_KEY, 0) else 0
        }
        return if (HookRuntime.hook(emit, "status_bar_icons/wifi-standard-map") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val id = fields[0].getInt(owner)
            val raw = chain.args[0] as? Int ?: return@hook chain.proceed()
            if (mode() != 1 || id != 2 || raw !in 4..5) return@hook chain.proceed()
            val code = version.get() ?: run {
                // Read the process application only inside a real native emission.
                val context = application.invoke(null) as? Context ?: return@hook chain.proceed()
                context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode
                    .also { version.set(it) }
            }
            if (code != 202602260L) return@hook chain.proceed()
            val original = fields[1].get(owner)?.takeIf { collector.isInstance(it) } ?: return@hook chain.proceed()
            val active = AtomicBoolean(true)
            val bridge = Proxy.newProxyInstance(loader, arrayOf(collector)) { proxy, method, args ->
                when {
                    method == delegateEmit -> {
                        val incoming = args?.getOrNull(0)
                        val value = if (active.get() && incoming is Int)
                            WifiStandardPolicy.recover(code, mode(), id, raw, incoming) ?: incoming
                        else incoming
                        try {
                            // Return the downstream suspension sentinel unchanged; never resume or replay.
                            delegateEmit.invoke(original, value, args?.getOrNull(1))
                        } catch (failure: InvocationTargetException) {
                            throw failure.targetException
                        }
                    }
                    method.name == "equals" -> proxy === args?.getOrNull(0)
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "toString" -> "HyperExtendWifiStandardCollector"
                    else -> error("Unexpected FlowCollector method: $method")
                }
            }
            fields[1].set(owner, bridge) // Declared interface, assignable type; never Unsafe.
            try {
                chain.proceed()
            } finally {
                // The exact mapper clears saved locals before its sole suspending emit.
                // Even if restoration fails or another owner takes over, this bridge becomes inert.
                active.set(false)
                runCatching { if (fields[1].get(owner) === bridge) fields[1].set(owner, original) }
                    .onFailure { ModuleLog.error("wifi-standard collector restoration failed", it) }
            }
        }) 1 else 0
    }
}
