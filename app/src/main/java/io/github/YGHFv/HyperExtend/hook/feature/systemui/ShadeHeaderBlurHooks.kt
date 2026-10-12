/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong

internal object ShadeHeaderBlurHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = ShadeHeaderBlurTargets
        val query = NotificationHooks.resolve(loader, t.query) ?: return 0
        val callers = t.callers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val context = query.declaringClass.getDeclaredField("context").apply {
            require(type == Context::class.java && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val supported = Class.forName(t.BLUR, false, loader).getDeclaredField("gradientBlurSupported").apply {
            require(type == Boolean::class.javaPrimitiveType && Modifier.isStatic(modifiers)); isAccessible = true
        }
        // The Flow calculations, canvas path and clip animator must all see the same
        // selector. Do not leave compiled callers using a different inlined decision.
        if (!(callers + query).all { HookRuntime.deoptimize(it, "${t.FEATURE}/caller") }) return 0
        val version = AtomicLong(0)
        return if (HookRuntime.hookAfter(query, "${t.FEATURE}/selector") { chain, original ->
            if (original != false || SafeModeRuntime.blocked || !settings.isOn(t.FEATURE)) return@hookAfter original
            runCatching {
                val host = context.get(chain.thisObject) as? Context ?: return@runCatching original
                val code = version.get().takeIf { it != 0L } ?: host.packageManager
                    .getPackageInfo("com.android.systemui", 0).compatibleVersionCode.also { version.set(it) }
                val display = host.hostDisplayIdOrNull()
                if (code != 202602260L || display != 0) return@runCatching original
                ShadeHeaderBlurPolicy.useGradient(false, true, code, display, supported.getBoolean(null))
            }.getOrDefault(original)
        }) 1 else 0
    }
}
