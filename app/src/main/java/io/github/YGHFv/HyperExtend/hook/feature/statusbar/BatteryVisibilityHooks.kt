/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.util.TypedValue
import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.util.concurrent.atomic.AtomicBoolean

internal object BatteryVisibilityHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val policy = BatteryVisibilityPolicy.from(settings)
        if (!policy.hidesAnything) return 0
        val t = BatteryStyleTargets
        val methods = (t.refresh + t.callers).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = BatteryVisibilityTargets.fields.associate { (name, type) ->
            name to StatusIconHost.field(loader, t.OWNER, name, type)
        }
        if (!methods.values.all { HookRuntime.deoptimize(it, "status_bar_icons/battery-caller") }) return 0
        val host = StatusIconHost()
        val ready = AtomicBoolean(false)
        val hooks = t.refresh.map { target ->
            HookRuntime.hookAfter(methods.getValue(target), "status_bar_icons/battery/${target.name}") { chain, result ->
                val view = chain.thisObject as? View
                if (ready.get() && view != null && host.eligible(view)) {
                    for (name in policy.hiddenViewFields(fields.getValue("mBatteryStyle").getInt(view)))
                        (fields.getValue(name).get(view) as? View)?.visibility = View.GONE
                    for (name in policy.hiddenTextFields())
                        (fields.getValue(name).get(view) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, 0f)
                }
                result
            }
        }
        // Like battery customization, these load-time settings require a restart to restore layout.
        ready.set(hooks.all { it })
        return if (ready.get()) hooks.size else 0
    }
}
