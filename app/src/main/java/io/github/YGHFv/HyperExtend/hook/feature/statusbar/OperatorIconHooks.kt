/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

internal object OperatorIconHooks {
    private const val OPTION = "status_bar_mobile.hide_volte"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(OPTION)) return 0
        val t = OperatorIconTargets
        val methods = (listOf(t.create) + t.callers).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        val config = Class.forName(t.CONFIG, false, loader)
        if (!methods.values.all { HookRuntime.deoptimize(it, "$OPTION/caller") }) return 0
        return if (HookRuntime.hookAfter(methods.getValue(t.create), "$OPTION/native-config") { chain, result ->
            val context = fields[0].get(chain.thisObject) as? Context
            val version = context?.packageManager?.getPackageInfo("com.android.systemui", 0)?.compatibleVersionCode
            if (result?.javaClass == config && StatusIconPolicy.hideOperatorIcon(
                    version, HookRuntime.settings().isOn(OPTION))) {
                // Complete host method constructs an unpublished object at its single return.
                // Change only its display flag; do not alter shared cached configs or telephony.
                fields[1].setBoolean(result, true)
            }
            result
        }) 1 else 0
    }
}
