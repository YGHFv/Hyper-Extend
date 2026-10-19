/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Adapted from RemoveNotifNumLimit; keep coordinator attachment intact on OS4.
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object NotificationCountLimitHooks {
    const val FEATURE = "notification_remove_count_limit"
    internal const val COORDINATOR = "com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator"
    internal const val CALLBACK = "$COORDINATOR\$\$ExternalSyntheticLambda0"

    fun install(loader: ClassLoader): Int {
        val coordinator = Reflect.loadClass(loader, COORDINATOR) ?: return 0
        val listener = Reflect.loadClass(loader, CALLBACK) ?: return 0
        // 只按目标结构放行：协调器/lambda 的外部引用与 onViewBound 参数必须同时匹配。
        val owner = Reflect.findField(listener, "f\$0")?.takeIf { it.type == coordinator } ?: return 0
        val entry = Reflect.loadClass(loader, "com.android.systemui.statusbar.notification.collection.NotificationEntry") ?: return 0
        val callback = listener.declaredMethods.singleOrNull {
            it.name == "onViewBound\$1" && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(entry))
        } ?: return 0
        return if (HookRuntime.hook(callback, "$FEATURE/onViewBound") { chain ->
                val supported = runCatching { owner.get(chain.thisObject) != null }.getOrDefault(false)
                // This listener only selects and dismisses overflow entries. Do not alter
                // NotifCollection.dismissNotification, app cancellations or system-server limits.
                if (supported) null else chain.proceed()
            }) 1 else 0
    }
}
