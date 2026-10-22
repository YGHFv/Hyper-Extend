/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 解除导航断充保护的场景判断。 */
internal object SystemFrameworkHookEntryHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("system_framework_hook_entry")) return 0
        val clazz = Reflect.loadClass(
            loader,
            "com.android.server.MiuiBatteryIntelligence\$BatteryNotificationListernerService",
        ) ?: return missing()
        val method = Reflect.findMethods(clazz, "isNavigationStatus", paramCount = 1)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return missing()
        return if (HookRuntime.hookReturning(
                method,
                "hook_entry/BatteryNotificationListernerService#isNavigationStatus",
                true,
            )
        ) 1 else 0
    }

    private fun missing(): Int {
        ModuleLog.warn("hook_entry: MiuiBatteryIntelligence navigation status target not found")
        return 0
    }
}
