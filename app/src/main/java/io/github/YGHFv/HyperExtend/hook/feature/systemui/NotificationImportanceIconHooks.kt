/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.service.notification.NotificationListenerService
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationImportanceIconTargets {
    private const val BUILDER = "com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsStoreBuilder"
    private const val MODEL = "com.android.systemui.statusbar.notification.shared.ActiveNotificationModel"
    val model = SystemUiMethod(BUILDER, "toModel", MODEL, listOf(SystemUiTargets.ENTRY))
    val suppress = SystemUiMethod(SystemUiTargets.ENTRY, "shouldSuppressVisualEffect", "boolean", listOf("int"))
    val callers = listOf(
        SystemUiMethod(BUILDER, "toModel", "com.android.systemui.statusbar.notification.shared.ActiveNotificationGroupModel",
            listOf("com.android.systemui.statusbar.notification.collection.GroupEntry")),
        SystemUiMethod("com.android.systemui.statusbar.notification.domain.interactor.RenderNotificationListInteractor\$\$ExternalSyntheticLambda0",
            "invoke", "java.lang.Object", listOf("java.lang.Object")),
    )
}

internal object NotificationImportanceIconHooks {
    fun install(loader: ClassLoader): Int {
        val targets = NotificationImportanceIconTargets
        val model = NotificationHooks.resolve(loader, targets.model) ?: return 0
        val suppress = NotificationHooks.resolve(loader, targets.suppress) ?: return 0
        val callers = targets.callers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val ranking = Reflect.findField(suppress.declaringClass, "mRanking")
            ?.takeIf { it.type == NotificationListenerService.Ranking::class.java } ?: return 0
        if (!(callers + model).all { HookRuntime.deoptimize(it, "notification_importance/icons/${it.name}") }) return 0
        val ready = AtomicBoolean(false)
        val logged = AtomicBoolean(false)
        val scope = NotificationImportanceIconScope()
        val queryHook = HookRuntime.hook(suppress, "notification_importance/statusBarEffect") { chain ->
            if (!ready.get()) return@hook chain.proceed()
            val level = (ranking.get(chain.thisObject) as? NotificationListenerService.Ranking)?.importance
            if (scope.suppress(chain.thisObject, chain.args[0] as? Int, level)) {
                if (logged.compareAndSet(false, true)) ModuleLog.info("notification_importance: low-ranking status-bar icon suppression reached")
                true
            } else chain.proceed()
        }
        val modelHook = HookRuntime.hook(model, "notification_importance/iconModel") { chain ->
            if (!ready.get()) return@hook chain.proceed()
            // Feed the native model's suppression flag before its cache/equality check. Never
            // mutate a published model or remove notifications from the shared rendered list.
            scope.withEntry(chain.args[0]) { chain.proceed() }
        }
        ready.set(queryHook && modelHook)
        return if (ready.get()) 2 else 0
    }
}
