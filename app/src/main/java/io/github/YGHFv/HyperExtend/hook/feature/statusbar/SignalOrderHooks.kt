/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

internal object SignalOrderHooks {
    const val FEATURE = "status_bar_signal_order"
    fun install(loader: ClassLoader): Int {
        val t = SignalOrderTargets
        val factory = NotificationHooks.resolve(loader, t.factory) ?: return 0
        val caller = NotificationHooks.resolve(loader, t.caller) ?: return 0
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        if (!listOf(factory, caller).all { HookRuntime.deoptimize(it, "$FEATURE/factory") }) return 0
        return if (HookRuntime.hookAfter(factory, "$FEATURE/factory") { chain, result ->
            val context = chain.args[0] as? Context ?: return@hookAfter result
            if (result == null || !HookRuntime.settings().isOn(FEATURE) ||
                context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode != 202602260L)
                return@hookAfter result
            @Suppress("UNCHECKED_CAST")
            val slots = fields[0].get(result) as? ArrayList<Any> ?: return@hookAfter result
            val viewOnly = fields[1].get(result) as? List<*> ?: return@hookAfter result
            val native = slots.toList()
            if (viewOnly.size != slots.size || viewOnly.indices.any { viewOnly[it] !== slots[it] } ||
                slots.any { !fields[2].declaringClass.isInstance(it) }) return@hookAfter result
            val names = slots.map { fields[2].get(it) as? String ?: return@hookAfter result }
            val published = slots.any { fields[3].get(it) != null || (fields[4].get(it) as? List<*>)?.isNotEmpty() == true }
            val order = SignalOrderPolicy.permutation(names, published) ?: return@hookAfter result
            // This factory returns a new, unpublished list. Keep slot identity and the native
            // unmodifiable view's backing list; never reorder holders or attached icon views.
            try { order.forEachIndexed { i, from -> slots[i] = native[from] } }
            catch (error: Throwable) {
                native.forEachIndexed { i, slot -> slots[i] = slot }
                throw error
            }
            result
        }) 1 else 0
    }
}
