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
import io.github.YGHFv.HyperExtend.core.ModuleLog

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
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.PercentageSettings.FEATURE) {
            PercentageHooks.installClassic(loader, settings)
        }
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.HeaderWeatherSettings.FEATURE) {
            HeaderWeatherHooks.install(loader, settings)
        }
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.NotificationBlendSettings.FEATURE) {
            NotificationBlendHooks.install(loader, settings)
        }
        installSystemUiFeature(settings, ShadeHeaderBlurTargets.FEATURE) { ShadeHeaderBlurHooks.install(loader, settings) }
        installSystemUiFeature(settings, SecurityFooterHooks.FEATURE) { SecurityFooterHooks.installClassic(loader, settings) }
        installSystemUiFeature(settings, ClassicQsSettings.FEATURE) { ClassicQsHooks.install(loader, settings) }
        installSystemUiFeature(settings, DimTileIconPolicy.FEATURE) { DimTileIconHooks.install(loader, settings) }
        installSystemUiFeature(settings, GmsTilePolicy.FEATURE) {
            GmsTileHooks.install(loader) + GmsTileEditorHooks.installClassic(loader)
        }
        installSystemUiFeature(settings, TaplusTilePolicy.FEATURE) {
            TaplusTileHooks.install(loader) + TaplusTileEditorHooks.installClassic(loader)
        }
        installSystemUiFeature(settings, SunlightTilePolicy.FEATURE) {
            SunlightTileHooks.install(loader) + SunlightTileHooks.installLifecycle(loader, settings)
        }
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.AudioMuteSettings.FEATURE) {
            AudioMuteHooks.install(loader)
        }
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.QsLabelSettings.FEATURE) {
            QsLabelHooks.install(loader, settings)
        }
        installSystemUiFeature(settings, io.github.YGHFv.HyperExtend.core.FlashlightStrengthSettings.FEATURE) {
            FlashlightStrengthHooks.install(loader, settings)
        }
        installSystemUiFeature(settings, StockTilesPolicy.FEATURE) {
            // 样式档在安装期只走其中一条路径：独立磁贴（样式一/二）装工厂，
            // 「内置移动网络磁贴」装原生详情页注入，两者互斥，不会同时装。
            val fiveG = runCatching { FiveGTileHooks.install(loader, settings) }.getOrElse {
                ModuleLog.error("stock_tiles: independent 5G factory unavailable; keep native candidates", it)
                0
            }
            val fiveGDetail = runCatching { FiveGDetailHooks.install(loader, settings) }.getOrElse {
                ModuleLog.error("stock_tiles: native 5G detail unavailable; keep native behaviour", it)
                0
            }
            fiveG + fiveGDetail + StockTilesHooks.installClassic(loader, settings)
        }
        installSystemUiFeature(settings, AutoCollapseHooks.FEATURE) { AutoCollapseHooks.install(loader, settings) }
        installSystemUiFeature(settings, "control_center_unlock_old") { unlockOld(loader) }
        // 合并后菜单跳转不再是「哪张卡片开着」，而是通知重要程度卡片里的一个选择项：
        // 默认跟随原生，只有显式选了「对应通知渠道」才装这个拦截器。这样只开重要程度，
        // 菜单跳转也不会被顺带改掉（合并前它俩共用这里、只装其中一个）。
        // 菜单跳转是通知重要程度卡片的配置项，所以它自己也受父开关约束：
        // 关掉重要程度后不应该还留着一段改菜单目标的 hook。
        val menuSettings = io.github.YGHFv.HyperExtend.core.NotificationImportanceSettings
        val menuTarget = settings.string(menuSettings.MENU_TARGET)
        if (settings.isOn(menuSettings.FEATURE) && menuSettings.channelRedirect(menuTarget)) {
            val count = try {
                channelSettings(loader)
            } catch (failure: Throwable) {
                ModuleLog.error("notification_importance/menu_target: installation failed", failure)
                0
            }
            if (count == 0) ModuleLog.warn("notification_importance/menu_target: no compatible hooks installed")
            add("notification_importance.menu_target=$count")
        }
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
        if (click != null && notification != null && HookRuntime.hook(click, "notification_importance/menuContext") { chain ->
                val previous = menuNotification.get()
                try {
                    val row = rowField.get(chain.thisObject)
                    menuNotification.set(notification.get(row) as? StatusBarNotification)
                    chain.proceed()
                } finally {
                    if (previous == null) menuNotification.remove() else menuNotification.set(previous)
                }
            }) count++
        if (HookRuntime.hook(method, "notification_importance/menuTarget") { chain ->
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
