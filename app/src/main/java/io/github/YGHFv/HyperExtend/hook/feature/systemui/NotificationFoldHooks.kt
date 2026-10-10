/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationFoldHooks {
    private const val FEATURE = "notification_disable_auto_fold"

    fun install(loader: ClassLoader): Int {
        val ignore = NotificationHooks.resolve(loader, SystemUiTargets.ignoreFold) ?: return 0
        val custom = NotificationHooks.resolve(loader, SystemUiTargets.customFold) ?: return 0
        val menu = NotificationHooks.resolve(loader, SystemUiTargets.foldMenuViews) ?: return 0
        val callers = SystemUiTargets.foldCallers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val menuCallers = SystemUiTargets.foldMenuCallers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val shade = callers[2]
        val owner = Reflect.findField(shade.declaringClass, "this\$0")
            ?.takeIf { it.type.name == SystemUiTargets.FOLD_COORDINATOR } ?: return 0
        val pending = Reflect.findField(owner.type, "mPendingNotifications")
            ?.takeIf { it.type == List::class.java } ?: return 0
        val menuSbn = Reflect.findField(menu.declaringClass, "mSbn")
            ?.takeIf { it.type.name == SystemUiTargets.EXPANDED_NOTIFICATION } ?: return 0
        val entrySbn = Reflect.findField(custom.parameterTypes.single(), "mSbn")
            ?.takeIf { it.type == menuSbn.type } ?: return 0
        val isFold = Reflect.findField(menuSbn.type, "mIsFold")
            ?.takeIf { it.type == Boolean::class.javaPrimitiveType } ?: return 0

        // Deoptimize callers, not just query methods: ART may have inlined either query.
        if (!(callers + menu + menuCallers).all { HookRuntime.deoptimize(it, "$FEATURE/${it.name}") }) return 0
        val ready = AtomicBoolean(false)
        val scope = NotificationFoldScope<Any>()
        var installed = 0
        if (HookRuntime.hook(ignore, "$FEATURE/shouldIgnoreEntry") { chain ->
                if (ready.get() && scope.ignoreAutomatic) true else chain.proceed()
            }) installed++
        if (HookRuntime.hook(custom, "$FEATURE/canCustomFold") { chain ->
                if (ready.get() && scope.suppressMenuFor(chain.args[0]?.let(entrySbn::get))) false else chain.proceed()
            }) installed++
        if (HookRuntime.hook(menu, "$FEATURE/createMenuViews") { chain ->
                if (!ready.get()) return@hook chain.proceed()
                val notification = menuSbn.get(chain.thisObject)
                // Omit only the fold action before native item reuse, width and accessibility setup.
                // Already-folded notifications retain the native move-out action.
                scope.inMenu(notification, notification?.let(isFold::getBoolean)) { chain.proceed() }
            }) installed++
        for (caller in callers) {
            if (HookRuntime.hook(caller, "$FEATURE/${caller.toGenericString()}") { chain ->
                    if (!ready.get()) return@hook chain.proceed()
                    if (caller == shade) (pending.get(owner.get(chain.thisObject)) as? MutableList<*>)?.clear()
                    scope.inAutomatic { chain.proceed() }
                }) installed++
        }
        // A partially installed adapter must leave both the menu and automatic folding native.
        ready.set(installed == callers.size + 3)
        return if (ready.get()) installed else 0
    }
}
