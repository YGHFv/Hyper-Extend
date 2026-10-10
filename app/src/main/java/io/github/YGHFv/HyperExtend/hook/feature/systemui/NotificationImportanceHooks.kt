/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.service.notification.NotificationListenerService
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object NotificationImportanceHooks {
    const val FEATURE = "notification_importance"
    private const val STACK = "com.android.systemui.statusbar.notification.collection.coordinator.StackCoordinator"
    val statsTarget = SystemUiMethod(STACK, "calculateNotifStats",
        "com.android.systemui.statusbar.notification.data.model.NotifStats", listOf("java.util.List"))

    fun install(loader: ClassLoader): Int {
        val icons = NotificationImportanceIconHooks.install(loader)
        return icons + installStats(loader)
    }

    private fun installStats(loader: ClassLoader): Int {
        val stats = NotificationHooks.resolve(loader, statsTarget) ?: return 0
        val flatten = stats.declaringClass.getDeclaredMethod("getFlatNotifEntryList", List::class.java).apply { isAccessible = true }
        val entry = Reflect.loadClass(loader, SystemUiTargets.ENTRY) ?: return 0
        val ranking = Reflect.findField(entry, "mRanking")
            ?.takeIf { it.type == NotificationListenerService.Ranking::class.java } ?: return 0
        Reflect.loadClass(loader, "$STACK\$attach\$1")?.getDeclaredMethod("onAfterRenderList", List::class.java)?.let {
            HookRuntime.deoptimize(it, "$FEATURE/statsCaller")
        }
        return if (HookRuntime.hook(stats, "$FEATURE/stats") { chain ->
                val flat = flatten.invoke(null, chain.args[0]) as? List<*>
                if (flat == null) chain.proceed() else {
                    // OS4 also sends the render list to other consumers. Only change stats;
                    // flatten groups/bundles first so a quiet summary cannot hide loud children.
                    val filtered = flat.filter { item ->
                        val importance = Reflect.attempt {
                            if (entry.isInstance(item)) (ranking.get(item) as? NotificationListenerService.Ranking)?.importance else null
                        }
                        NotificationImportancePolicy.counted(importance)
                    }
                    if (filtered.size == flat.size) chain.proceed() else chain.proceed(arrayOf(filtered))
                }
            }) 1 else 0
    }

    fun installSettings(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, FEATURE) { settingsPage(loader) }
    }

    private fun settingsPage(loader: ClassLoader): Int = NotificationImportanceSettingsHooks(loader).install()
}
