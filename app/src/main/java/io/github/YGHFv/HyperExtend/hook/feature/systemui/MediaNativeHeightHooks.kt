/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

internal object MediaNativeHeightHooks {
    val getter = SystemUiMethod("android.widget.ProgressBar", "getMaxHeight", "int")
    val primary = SystemUiMethod(MediaProgressTargets.BAR, "drawPrimaryMode", "void", listOf("android.graphics.Canvas"))
    fun install(loader: ClassLoader, feature: String, scope: MediaNativeHeightScope): Boolean {
        val method = NotificationHooks.resolve(loader, getter) ?: return false
        val callers = listOf(primary, MediaProgressTargets.draw).map { NotificationHooks.resolve(loader, it) ?: return false }
        if (!callers.all { HookRuntime.deoptimize(it, "$feature/native-height-caller") }) return false
        return HookRuntime.hookAfter(method, "$feature/native-height") { chain, result ->
            if (SafeModeRuntime.blocked || !HookRuntime.settings().isOn(feature)) result
            else scope.height(chain.thisObject) ?: result
        }
    }
}
