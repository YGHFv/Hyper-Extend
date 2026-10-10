/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.SYSTEM_UI_FOCUS_PACKAGES
import io.github.YGHFv.HyperExtend.core.SYSTEM_UI_MONET_COLOR
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.core.NavigationHandleSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object SystemUiCustomHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, NavigationHandleSettings.FEATURE) { NavigationHandleHooks.install(loader, settings) }
        installSystemUiFeature(settings, "systemui_monet_custom") { monet(loader, settings) }
        installSystemUiFeature(settings, "notification_unlock_focus") { focus(loader, settings) }
        installSystemUiFeature(settings, "notification_disable_auto_fold") { NotificationFoldHooks.install(loader) }
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
        val overrideLock = Any()
        return methods.count { method ->
            HookRuntime.hook(method, "media_unlock_custom_actions/${method.toGenericString()}") { chain ->
                val target = instance.get(null) ?: return@hook chain.proceed()
                val fields = arrayOf(cloud, local)
                withTemporaryLists(overrideLock, { fields[it].get(target) }, { i, value -> fields[i].set(target, value) }) {
                    chain.proceed()
                }
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

}
