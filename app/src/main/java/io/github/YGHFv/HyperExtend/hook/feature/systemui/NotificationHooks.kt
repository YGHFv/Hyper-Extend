/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.os.PowerManager
import android.os.Build
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Method

internal object NotificationHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, NotificationImportanceHooks.FEATURE) { NotificationImportanceHooks.install(loader) }
        installSystemUiFeature(settings, "lockscreen_show_notifications") { constant(loader, SystemUiTargets.showOnKeyguard, true) }
        installSystemUiFeature(settings, "lockscreen_keep_notifications") { keepNotifications(loader) }
        val muteInteractive = settings.isOn("notification_mute_when_interactive")
        val zenFix = settings.isOn("notification_zen_fix")
        if (muteInteractive || zenFix) {
            val count = runCatching { muteNotifications(loader, muteInteractive, zenFix) }.getOrElse {
                ModuleLog.error("notification alert policy installation failed", it)
                0
            }
            if (muteInteractive) add("notification_mute_when_interactive=$count")
            if (zenFix) add("notification_zen_fix=$count")
        }
        installSystemUiFeature(settings, "notification_disable_transparent") { constant(loader, SystemUiTargets.transparent, false) }
        installSystemUiFeature(settings, "notification_freeform") { freeform(loader) }
    }

    internal fun resolve(loader: ClassLoader, spec: SystemUiMethod): Method? {
        val method = Reflect.loadClass(loader, spec.owner)?.declaredMethods?.firstOrNull(spec::matches)
        if (method == null) ModuleLog.warn("systemui target missing: ${spec.owner}#${spec.name}${spec.parameters}")
        return method?.also { it.isAccessible = true }
    }

    private fun constant(loader: ClassLoader, spec: SystemUiMethod, value: Boolean): Int {
        val method = resolve(loader, spec) ?: return 0
        return if (HookRuntime.hookReturning(method, "systemui/${spec.owner}#${spec.name}", value)) 1 else 0
    }

    private fun keepNotifications(loader: ClassLoader): Int {
        val method = resolve(loader, SystemUiTargets.forceHideOnKeyguard) ?: return 0
        val entry = Reflect.loadClass(loader, SystemUiTargets.ENTRY) ?: return 0
        val sbn = Reflect.findField(entry, "mSbn") ?: return 0
        val shown = Reflect.findField(sbn.type, "mHasShownAfterUnlock")?.takeIf { it.type == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hook(method, "lockscreen_keep_notifications/forceHideOnKeyguard") { chain ->
                // Change only the "already seen" flag. Do not bypass profile/privacy filtering.
                Reflect.attempt { sbn.get(chain.args[0])?.let { shown.setBoolean(it, false) } }
                chain.proceed()
            }) 1 else 0
    }

    private fun muteNotifications(loader: ClassLoader, muteInteractive: Boolean, zenFix: Boolean): Int {
        val method = resolve(loader, SystemUiTargets.alert) ?: return 0
        val context = Reflect.findField(method.declaringClass, "mContext") ?: return 0
        return if (HookRuntime.hook(method, "notification_alert_policy/buzzBeepBlink") { chain ->
                val host = Reflect.attempt { context.get(chain.thisObject) as? Context }
                val interactive = Reflect.attempt {
                    if (muteInteractive) host?.getSystemService(PowerManager::class.java)?.isInteractive else null
                }
                val filter = Reflect.attempt {
                    if (zenFix) host?.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter else null
                }
                if (NotificationAlertPolicy.mute(muteInteractive, interactive, zenFix, filter)) null else chain.proceed()
            }) 1 else 0
    }

    private fun freeform(loader: ClassLoader): Int {
        val update = resolve(loader, SystemUiTargets.miniWindowBar) ?: return 0
        val canSlide = resolve(loader, SystemUiTargets.canSlide) ?: return 0
        val updating = ThreadLocal<Boolean>()
        // OS4 computes the bar and its background from canSlide inside updateMiniWindowBar.
        // Changing the field afterwards leaves a stale/invisible affordance.
        val query = HookRuntime.hook(canSlide, "notification_freeform/canNotificationSlide") { chain ->
            val pending = chain.args[1] as? PendingIntent
            if (Build.VERSION.SDK_INT >= 31 && updating.get() == true &&
                !(chain.args[0] as? String).isNullOrBlank() && pending?.isActivity == true) {
                true
            } else chain.proceed()
        }
        if (!query) return 0
        val gate = HookRuntime.hook(update, "notification_freeform/updateMiniWindowBar") { chain ->
            val previous = updating.get()
            updating.set(true)
            try {
                chain.proceed()
            } finally {
                if (previous == null) updating.remove() else updating.set(previous)
            }
        }
        return if (gate) 2 else 0
    }
}
