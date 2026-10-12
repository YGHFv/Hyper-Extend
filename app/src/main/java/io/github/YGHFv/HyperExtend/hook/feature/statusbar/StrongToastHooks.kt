/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.util.concurrent.atomic.AtomicBoolean

internal object StrongToastHooks {
    const val FEATURE = "status_bar_hide_strong_toast"
    // 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
    @SuppressLint("NewApi")
    fun install(loader: ClassLoader): Int {
        val t = StrongToastTargets
        val methods = listOf(t.entry, t.delivery, t.caller).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val context = t.context.let { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$FEATURE/caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = StrongToastScope()
        val entry = HookRuntime.hook(methods.getValue(t.entry), "$FEATURE/command") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val bundle = chain.args[2] as? Bundle ?: return@hook chain.proceed()
            val ctx = context.get(owner) as? Context ?: return@hook chain.proceed()
            if (!ready.get() || !StrongToastPolicy.eligible(HookRuntime.settings().isOn(FEATURE),
                    ctx.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                    ctx.display?.displayId, chain.args[1] as? String)) return@hook chain.proceed()
            // Keep native JSON validation/logging. No globally cancelled pre-draw, window,
            // command broadcast, or interference with internal charge animation/cleanup.
            scope.within(owner, bundle) { chain.proceed() }
        }
        val delivery = HookRuntime.hook(methods.getValue(t.delivery), "$FEATURE/parsed-delivery") { chain ->
            if (ready.get() && HookRuntime.settings().isOn(FEATURE) && chain.args[1] != null &&
                scope.matches(chain.thisObject, chain.args[0])) null else chain.proceed()
        }
        ready.set(entry && delivery)
        return if (ready.get()) 2 else 0
    }
}
