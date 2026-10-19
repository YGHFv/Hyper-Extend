/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.view.View
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.WindowDecorationSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object WindowDecorationHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        val keys = listOf(WindowDecorationSettings.HIDE_DOT, WindowDecorationSettings.DISABLE_MENU,
            WindowDecorationSettings.DISABLE_INFINITY, WindowDecorationSettings.HIDE_BOTTOM)
        if (keys.none(settings::isOn)) return@buildList
        // 每个功能只按目标类、方法签名、字段类型和调用点解析；不绑定宿主版本或库哈希。
        installSystemUiFeature(settings, WindowDecorationSettings.HIDE_DOT) { hideDot(loader, settings) }
        installSystemUiFeature(settings, WindowDecorationSettings.DISABLE_MENU) { disableMenu(loader, settings) }
        installSystemUiFeature(settings, WindowDecorationSettings.DISABLE_INFINITY) { infinity(loader, settings) }
        installSystemUiFeature(settings, WindowDecorationSettings.HIDE_BOTTOM) { bottom(loader, settings) }
    }

    private fun hideDot(loader: ClassLoader, settings: HookSettings): Int {
        val method = NotificationHooks.resolve(loader, WindowDecorationTargets.dotDraw) ?: return 0
        return if (HookRuntime.hook(method, "window/dot") { chain ->
            if (settings.isOn(WindowDecorationSettings.HIDE_DOT)) null else chain.proceed()
        }) 1 else 0
    }

    private fun disableMenu(loader: ClassLoader, settings: HookSettings): Int {
        val t = WindowDecorationTargets
        val method = NotificationHooks.resolve(loader, t.menu) ?: return 0
        val caller = NotificationHooks.resolve(loader, t.menuCaller) ?: return 0
        if (!HookRuntime.deoptimize(caller, "window/menu-caller")) return 0
        return if (HookRuntime.hook(method, "window/menu") { chain ->
            if (settings.isOn(WindowDecorationSettings.DISABLE_MENU)) null else chain.proceed()
        }) 1 else 0
    }

    private fun infinity(loader: ClassLoader, settings: HookSettings): Int {
        val t = WindowDecorationTargets
        val methods = (listOf(t.infinity, t.event, t.findTask) + t.gestureCallers)
            .associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!methods.values.all { HookRuntime.deoptimize(it, "window/infinity-caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = WindowGestureScope()
        val query = HookRuntime.hookAfter(methods.getValue(t.infinity), "window/infinity-query") { _, original ->
            val enabled = ready.get() && settings.isOn(WindowDecorationSettings.DISABLE_INFINITY)
            // Lazy initialization can occur inside a gesture; scope alone must not alter its singleton selection.
            val directGesture = enabled && scope.active && WindowDecorationPolicy.directGestureCaller(
                Thread.currentThread().stackTrace.map { it.className to it.methodName })
            WindowDecorationPolicy.infinityResult(original as Boolean,
                enabled, directGesture)
        }
        // Never change singleton selection in <clinit>, orientation, task creation or size policies.
        val callers = listOf(t.event, t.findTask).map { method ->
            HookRuntime.hook(methods.getValue(method), "window/infinity-gesture") { chain ->
                scope.within { chain.proceed() }
            }
        }
        ready.set(query && callers.all { it })
        return if (ready.get()) 3 else 0
    }

    private fun bottom(loader: ClassLoader, settings: HookSettings): Int {
        val t = WindowDecorationTargets
        val methods = (listOf(t.freeformHeight, t.bottomHeight, t.bottomDraw, t.rootView, t.findTask) + t.bottomCallers)
            .associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val mode = StatusIconHost.field(loader, t.BASE, "mWindowingMode", "int")
        val root = StatusIconHost.field(loader, t.BASE, "mMiuiDecorationRootViewHost", t.ROOT_HOST)
        if (!methods.values.all { HookRuntime.deoptimize(it, "window/bottom-caller") }) return 0
        val ready = AtomicBoolean(false)
        val owners = WeakHashMap<View, WeakReference<Any>>()
        val height = HookRuntime.hookAfter(methods.getValue(t.freeformHeight), "window/freeform-height") { _, original ->
            if (ready.get() && settings.isOn(WindowDecorationSettings.HIDE_BOTTOM)) 0 else original
        }
        val capture = HookRuntime.hookAfter(methods.getValue(t.bottomHeight), "window/bottom-owner") { chain, original ->
            runCatching {
                val owner = chain.thisObject ?: return@runCatching
                val host = root.get(owner) ?: return@runCatching
                val view = methods.getValue(t.rootView).invoke(host) as? View ?: return@runCatching
                synchronized(owners) { owners[view] = WeakReference(owner) }
            }
            original
        }
        val draw = HookRuntime.hook(methods.getValue(t.bottomDraw), "window/bottom-draw") { chain ->
            val windowMode = runCatching {
                val view = chain.thisObject as? View ?: return@runCatching null
                synchronized(owners) { owners[view]?.get() }?.let(mode::getInt)
            }.getOrNull()
            if (WindowDecorationPolicy.hideBottom(ready.get() && settings.isOn(WindowDecorationSettings.HIDE_BOTTOM), windowMode)) null
            else chain.proceed()
        }
        ready.set(height && capture && draw)
        return if (ready.get()) 3 else 0
    }
}
