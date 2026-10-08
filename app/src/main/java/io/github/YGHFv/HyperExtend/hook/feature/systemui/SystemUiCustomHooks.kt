/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.SYSTEM_UI_FOCUS_PACKAGES
import io.github.YGHFv.HyperExtend.core.SYSTEM_UI_MONET_COLOR
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object SystemUiCustomHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, "systemui_monet_custom") { monet(loader, settings) }
        installSystemUiFeature(settings, "notification_unlock_focus") { focus(loader, settings) }
        installSystemUiFeature(settings, "notification_disable_auto_fold") { disableAutoFold(loader) }
        installSystemUiFeature(settings, "media_unlock_custom_actions") { mediaActions(loader) }
    }

    private fun mediaActions(loader: ClassLoader): Int {
        val manager = Reflect.loadClass(loader, SystemUiTargets.focusState.owner) ?: return 0
        val instance = Reflect.findField(manager, "sINSTANCE") ?: return 0
        val cloud = Reflect.findField(manager, "mHiddenCustomActionsList") ?: return 0
        val local = Reflect.findField(manager, "mHiddenCustomActionsListLocal") ?: return 0
        if (cloud.type != List::class.java || local.type != List::class.java) return 0
        val methods = listOf(SystemUiTargets.mediaAction, SystemUiTargets.mediaActionRun)
            .map { NotificationHooks.resolve(loader, it) ?: return 0 }
        return methods.count { method ->
            HookRuntime.hook(method, "media_unlock_custom_actions/${method.toGenericString()}") { chain ->
                Reflect.attempt {
                    instance.get(null)?.let { target ->
                        cloud.set(target, arrayListOf<String>())
                        local.set(target, arrayListOf<String>())
                    }
                }
                // OS4 checks again when the action is clicked, after the icon was built.
                // Keep the host's original transport controls and custom action extras.
                chain.proceed()
            }
        }
    }

    private fun monet(loader: ClassLoader, settings: HookSettings): Int {
        val color = SystemUiCustomSettings.color(settings.string(SYSTEM_UI_MONET_COLOR)) ?: return 0
        val method = NotificationHooks.resolve(loader, SystemUiTargets.themeOverlays) ?: return 0
        return if (HookRuntime.hook(method, "systemui_monet_custom/createOverlays") { chain ->
                chain.proceed(arrayOf<Any?>(color))
            }) 1 else 0
    }

    private fun focus(loader: ClassLoader, settings: HookSettings): Int {
        val packages = SystemUiCustomSettings.packages(settings.string(SYSTEM_UI_FOCUS_PACKAGES))
        if (packages.isEmpty()) return 0
        return listOf(SystemUiTargets.focusState, SystemUiTargets.focusAppState).count { spec ->
            val method = NotificationHooks.resolve(loader, spec) ?: return@count false
            HookRuntime.hookAfter(method, "notification_unlock_focus/${spec.name}") { chain, original ->
                if (chain.args[1] in packages) 1 else original
            }
        }
    }

    private fun disableAutoFold(loader: ClassLoader): Int {
        val ignore = NotificationHooks.resolve(loader, SystemUiTargets.ignoreFold) ?: return 0
        val callers = SystemUiTargets.foldCallers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val shade = callers[2]
        val owner = Reflect.findField(shade.declaringClass, "this\$0") ?: return 0
        val pending = Reflect.findField(owner.type, "mPendingNotifications") ?: return 0
        val inAutomaticFold = ThreadLocal<Boolean>()
        val query = HookRuntime.hook(ignore, "notification_disable_auto_fold/shouldIgnoreEntry") { chain ->
            if (inAutomaticFold.get() == true) true else chain.proceed()
        }
        if (!query) return 0
        return 1 + callers.count { caller ->
            HookRuntime.hook(caller, "notification_disable_auto_fold/${caller.toGenericString()}") { chain ->
                if (caller == shade) Reflect.attempt {
                    (pending.get(owner.get(chain.thisObject)) as? MutableList<*>)?.clear()
                }
                val previous = inAutomaticFold.get()
                inAutomaticFold.set(true)
                try {
                    // Scope the exemption to automatic history folding, not unrelated
                    // notification filters or explicit user moves into the folded section.
                    chain.proceed()
                } finally {
                    if (previous == null) inAutomaticFold.remove() else inAutomaticFold.set(previous)
                }
            }
        }
    }
}
