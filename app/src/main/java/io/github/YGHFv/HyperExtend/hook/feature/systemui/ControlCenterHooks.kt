/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.os.Build
import android.provider.Settings
import android.service.notification.StatusBarNotification
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.core.ClassicQsSettings

internal object ControlCenterHooks {
    private val menuNotification = ThreadLocal<StatusBarNotification?>()

    fun installSettings(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, "control_center_unlock_old") {
            val type = Reflect.loadClass(loader, "com.android.settings.utils.StatusBarUtils")
                ?: return@installSystemUiFeature 0
            val method = type.getDeclaredMethod("isForceUseControlPanel", Context::class.java)
            if (!HookRuntime.hookReturning(method, "control_center_unlock_old/settingsSelector", false)) return@installSystemUiFeature 0
            Reflect.loadClass(loader, "com.android.settings.NotificationControlCenterSettings")?.let { screen ->
                for (name in listOf("setupControlCenter", "onCreate", "updateControlCenterExpandCard")) {
                    Reflect.findMethods(screen, name).forEach { HookRuntime.deoptimize(it, "control_center_unlock_old/selectorCaller") }
                }
            }
            1
        }
    }
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, ClassicQsSettings.FEATURE) { ClassicQsHooks.install(loader, settings) }
        installSystemUiFeature(settings, DimTileIconPolicy.FEATURE) { DimTileIconHooks.install(loader, settings) }
        installSystemUiFeature(settings, StockTilesPolicy.FEATURE) { StockTilesHooks.installClassic(loader, settings) }
        installSystemUiFeature(settings, AutoCollapseHooks.FEATURE) { AutoCollapseHooks.install(loader, settings) }
        installSystemUiFeature(settings, "control_center_unlock_old") { unlockOld(loader) }
        // Both entries use one interceptor and preserve the host's menu dismissal/user ID.
        val channelFeature = if (settings.isOn("notification_channel_settings")) "notification_channel_settings"
            else NotificationImportanceHooks.FEATURE
        installSystemUiFeature(settings, channelFeature) { channelSettings(loader) }
    }

    private fun unlockOld(loader: ClassLoader): Int {
        val repo = Reflect.loadClass(loader, SystemUiTargets.CONTROL_SETTINGS) ?: return 0
        val force = Reflect.findField(repo, "forceUseControlCenter")
            ?.takeIf { it.type == Boolean::class.javaPrimitiveType } ?: return 0
        val emit = NotificationHooks.resolve(loader, SystemUiTargets.controlSettingsEmit) ?: return 0
        val owner = Reflect.findField(emit.declaringClass, "this\$0")?.takeIf { it.type == repo } ?: return 0
        // Eagerly-collected Flow can run before the constructor's after-hook. Clear the
        // force flag at its actual consumer as well, without replacing the user's setting.
        val consumer = HookRuntime.hook(emit, "control_center_unlock_old/settingsEmit") { chain ->
            Reflect.attempt { owner.get(chain.thisObject)?.let { force.setBoolean(it, false) } }
            chain.proceed()
        }
        if (!consumer) return 0
        return 1 + repo.declaredConstructors.count { constructor ->
            HookRuntime.hookAfter(constructor, "control_center_unlock_old/${constructor.toGenericString()}") { chain, original ->
                force.setBoolean(chain.thisObject, false)
                original
            }
        }
    }

    private fun channelSettings(loader: ClassLoader): Int {
        val method = NotificationHooks.resolve(loader, SystemUiTargets.notificationSettings) ?: return 0
        val launch = Context::class.java.getDeclaredMethod("startActivityAsUser", Intent::class.java, UserHandle::class.java)
        val userForUid = UserHandle::class.java.getDeclaredMethod("getUserHandleForUid", Int::class.javaPrimitiveType)
        launch.isAccessible = true
        userForUid.isAccessible = true
        val clickType = Reflect.loadClass(loader,
            "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow\$\$ExternalSyntheticLambda5")
        val click = clickType?.getDeclaredMethod("onClick", android.view.View::class.java)
        val rowField = clickType?.let { Reflect.findField(it, "f\$0") }
        val notification = rowField?.type?.let { Reflect.findField(it, "mSbn") }
        var count = 0
        if (click != null && notification != null && HookRuntime.hook(click, "notification_channel_settings/menuContext") { chain ->
                val previous = menuNotification.get()
                try {
                    val row = rowField.get(chain.thisObject)
                    menuNotification.set(notification.get(row) as? StatusBarNotification)
                    chain.proceed()
                } finally {
                    if (previous == null) menuNotification.remove() else menuNotification.set(previous)
                }
            }) count++
        if (HookRuntime.hook(method, "notification_channel_settings/startAppNotificationSettings") { chain ->
                val context = chain.args[0] as? Context
                val pkg = chain.args[1] as? String
                val uid = chain.args[3] as? Int
                val channel = chain.args[4] as? String
                val opened = if (context != null && !pkg.isNullOrBlank() && uid != null && uid >= 0 && !channel.isNullOrBlank()) {
                    Reflect.attempt {
                        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                            setPackage("com.android.settings")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                            putExtra(Settings.EXTRA_CHANNEL_ID, channel)
                            putExtra("app_uid", uid)
                            val sbn = menuNotification.get()
                            val shortcut = if (Build.VERSION.SDK_INT >= 30) NotificationChannelPolicy.conversation(
                                pkg, uid, channel, sbn?.packageName, sbn?.uid,
                                sbn?.notification?.channelId, sbn?.notification?.shortcutId) else null
                            if (Build.VERSION.SDK_INT >= 30 && shortcut != null) putExtra(Settings.EXTRA_CONVERSATION_ID, shortcut)
                        }
                        launch.invoke(context, intent, userForUid.invoke(null, uid))
                        true
                    } == true
                } else false
                // Failure/empty channel falls back to the original app settings. The menu
                // still performs its own modal dismissal and panel collapse afterwards.
                if (opened) null else chain.proceed()
            }) count++
        return count
    }
}
