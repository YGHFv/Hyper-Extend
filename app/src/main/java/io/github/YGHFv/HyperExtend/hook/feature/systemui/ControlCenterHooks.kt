/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object ControlCenterHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, "control_center_auto_collapse") { autoCollapse(loader) }
        installSystemUiFeature(settings, "control_center_unlock_old") { unlockOld(loader) }
        installSystemUiFeature(settings, "notification_channel_settings") { channelSettings(loader) }
    }

    private fun autoCollapse(loader: ClassLoader): Int {
        val click = NotificationHooks.resolve(loader, SystemUiTargets.tileClick) ?: return 0
        val collapse = NotificationHooks.resolve(loader, SystemUiTargets.collapsePanels) ?: return 0
        val hostField = Reflect.findField(click.declaringClass, "mHost") ?: return 0
        val stateField = Reflect.findField(click.declaringClass, "mState") ?: return 0
        val tileSpec = Reflect.findField(click.declaringClass, "mTileSpec") ?: return 0
        val stateValue = Reflect.findField(stateField.type, "state") ?: return 0
        val restricted = Reflect.findField(stateField.type, "disabledByPolicy") ?: return 0
        return if (HookRuntime.hookAfter(click, "control_center_auto_collapse/click") { chain, original ->
                val state = stateField.get(chain.thisObject)
                val host = hostField.get(chain.thisObject)
                if (state != null && stateValue.getInt(state) != 0 && !restricted.getBoolean(state) &&
                    tileSpec.get(chain.thisObject) != "edit" && collapse.declaringClass.isInstance(host)) {
                    collapse.invoke(host)
                }
                original
            }) 1 else 0
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
        return if (HookRuntime.hook(method, "notification_channel_settings/startAppNotificationSettings") { chain ->
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
                        }
                        launch.invoke(context, intent, userForUid.invoke(null, uid))
                        true
                    } == true
                } else false
                // Failure/empty channel falls back to the original app settings. The menu
                // still performs its own modal dismissal and panel collapse afterwards.
                if (opened) null else chain.proceed()
            }) 1 else 0
    }
}
