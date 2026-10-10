/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Adapted from RemoveNotifNumLimit; keep coordinator attachment intact on OS4.
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object NotificationCountLimitHooks {
    const val FEATURE = "notification_remove_count_limit"
    internal const val COORDINATOR = "com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator"
    internal const val CALLBACK = "$COORDINATOR\$\$ExternalSyntheticLambda0"

    fun install(loader: ClassLoader): Int {
        val coordinator = Reflect.loadClass(loader, COORDINATOR) ?: return 0
        val listener = Reflect.loadClass(loader, CALLBACK) ?: return 0
        val owner = Reflect.findField(listener, "f\$0")?.takeIf { it.type == coordinator } ?: return 0
        val context = Reflect.findField(coordinator, "mContext")?.takeIf { it.type == Context::class.java } ?: return 0
        val entry = Reflect.loadClass(loader, "com.android.systemui.statusbar.notification.collection.NotificationEntry") ?: return 0
        val callback = listener.declaredMethods.singleOrNull {
            it.name == "onViewBound\$1" && it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf(entry))
        } ?: return 0
        var verified: Boolean? = null
        return if (HookRuntime.hook(callback, "$FEATURE/onViewBound") { chain ->
                val supported = verified ?: run {
                    val ctx = context.get(owner.get(chain.thisObject)) as? Context
                    val version = ctx?.let { runCatching { it.packageManager.getPackageInfo("com.android.systemui", 0).longVersionCode }.getOrNull() }
                    (version == 202602260L).also {
                        if (version != null) verified = it
                        if (!it) ModuleLog.warn("$FEATURE: unverified host; keeping count limit")
                    }
                }
                // This listener only selects and dismisses overflow entries. Do not alter
                // NotifCollection.dismissNotification, app cancellations or system-server limits.
                if (supported) null else chain.proceed()
            }) 1 else 0
    }
}
