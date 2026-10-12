/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.util.concurrent.atomic.AtomicBoolean

internal object MobileMarkerHooks {
    private const val FEATURE = "status_bar_mobile"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (listOf("hide_roaming", "hide_indicator", "hide_vowifi").none { settings.isOn("$FEATURE.$it") }) return 0
        val t = MobileMarkerTargets
        val methods = (t.emitters + t.collectors + t.pairAccess).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        val pair = Class.forName("kotlin.Pair", false, loader)
        val constructor = pair.getDeclaredConstructor(Any::class.java, Any::class.java).apply { isAccessible = true }
        if (!(t.emitters + t.collectors).all { HookRuntime.deoptimize(methods.getValue(it), "$FEATURE/marker-caller") }) return 0
        val host = StatusIconHost()
        val ready = AtomicBoolean(false)
        val hooks = t.emitters.mapIndexed { index, target ->
            HookRuntime.hook(methods.getValue(target), "$FEATURE/marker-$index") { chain ->
                if (!ready.get()) return@hook chain.proceed()
                val marker = MobileMarkerPolicy.marker(index == 0, fields[index * 2].getInt(chain.thisObject))
                    ?: return@hook chain.proceed()
                val view = fields[index * 2 + 1].get(chain.thisObject) as? View ?: return@hook chain.proceed()
                if (!host.eligible(view)) return@hook chain.proceed()
                val option = when (marker) {
                    MobileMarkerPolicy.Marker.VOWIFI -> "hide_vowifi"
                    MobileMarkerPolicy.Marker.ACTIVITY -> "hide_indicator"
                    else -> "hide_roaming"
                }
                if (!HookRuntime.settings().isOn("$FEATURE.$option")) return@hook chain.proceed()
                val input = chain.args[0]
                val replacement: Any = if (marker == MobileMarkerPolicy.Marker.VOWIFI ||
                    marker == MobileMarkerPolicy.Marker.SMALL_ROAMING) {
                    if (input !is Boolean) return@hook chain.proceed()
                    false
                } else {
                    if (input == null || input.javaClass != pair) return@hook chain.proceed()
                    val first = methods.getValue(t.pairAccess[0]).invoke(input) as? Boolean ?: return@hook chain.proceed()
                    val second = methods.getValue(t.pairAccess[1]).invoke(input) as? Boolean ?: return@hook chain.proceed()
                    val values = MobileMarkerPolicy.hiddenPair(marker, first, second) ?: return@hook chain.proceed()
                    constructor.newInstance(values.first, values.second)
                }
                // Original emitter still updates the animator/container and returns native Unit.
                // No upstream Flow/VM/OperatorConfig write; native subscription/cancellation stay intact.
                chain.proceed(arrayOf(replacement, chain.args[1]))
            }
        }
        ready.set(hooks.all { it })
        return if (ready.get()) hooks.size else 0
    }
}
