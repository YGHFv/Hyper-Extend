/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.content.Context
import android.os.Build
import android.os.UserManager
import io.github.YGHFv.HyperExtend.core.NotificationManagementSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationManagementHooks {
    private fun hostMatches() = Build.VERSION.SDK_INT == 37 && Build.FINGERPRINT == NotificationManagementTargets.FINGERPRINT
    private fun enabled(settings: HookSettings) = !SafeModeRuntime.blocked && settings.isOn(NotificationManagementSettings.FEATURE)

    @SuppressLint("NewApi")
    private fun settingsHost(context: Context): Boolean = runCatching {
        val user = context.getSystemService(UserManager::class.java) ?: return@runCatching false
        user.isSystemUser && !user.isManagedProfile && context.packageName == "com.android.settings" &&
            context.packageManager.getPackageInfo("com.android.settings", 0).compatibleVersionCode == 37L
    }.getOrDefault(false)

    fun installSettings(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, NotificationManagementSettings.FEATURE) {
            if (!hostMatches()) 0 else settingsAdapter(loader, settings)
        }
    }

    private fun settingsAdapter(loader: ClassLoader, settings: HookSettings): Int {
        val t = NotificationManagementTargets
        val methods = t.settingsMethods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, type) -> name to StatusIconHost.field(loader, owner, name, type) }
        if (!methods.values.all { HookRuntime.deoptimize(it, "manage_notifications/settings-caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = NativeCallScope<Pair<Context, String>>()
        val classify = HookRuntime.hookAfter(methods.getValue(t.row), "manage_notifications/ui-row") { chain, result ->
            val context = chain.args[0] as? Context
            if (ready.get() && enabled(settings) && result != null && context != null && settingsHost(context) &&
                NotificationManagementPolicy.ownerUid(fields.getValue("uid").getInt(result))) {
                // Only the notification page's row changes, never ApplicationInfo or channel flags.
                // Native setupBlock still applies notification permission, enterprise and ECM restrictions.
                fields.getValue("systemApp").setBoolean(result, false)
                fields.getValue("lockedChannelId").set(result, null)
            }
            result
        }
        val query = HookRuntime.hookAfter(methods.getValue(t.forced), "manage_notifications/forced-query") { chain, original ->
            val active = scope.value
            if (ready.get() && enabled(settings) && active != null && active.first === chain.args[0] && active.second == chain.args[1]) false
            else original
        }
        val setter = HookRuntime.hook(methods.getValue(t.toggle), "manage_notifications/native-toggle") { chain ->
            val context = chain.args[0] as? Context
            val pkg = chain.args[1] as? String
            val active = if (ready.get() && enabled(settings) && context != null && !pkg.isNullOrBlank() && settingsHost(context)) context to pkg else null
            scope.within(active) { chain.proceed() }
        }
        ready.set(classify && query && setter)
        return if (ready.get()) 3 else 0
    }

    fun installServer(loader: ClassLoader, settings: HookSettings): Int {
        if (!enabled(settings) || !hostMatches()) return 0
        val t = NotificationManagementTargets
        val methods = t.serverMethods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!methods.values.all { HookRuntime.deoptimize(it, "manage_notifications/server-caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = NativeCallScope<String>()
        val query = HookRuntime.hookAfter(methods.getValue(t.blockable), "manage_notifications/channel-query") { chain, original ->
            val channel = chain.thisObject as? NotificationChannel
            if (ready.get() && enabled(settings) && scope.value != null && channel?.id == scope.value &&
                NotificationManagementPolicy.directChannelUpdate(Thread.currentThread().stackTrace.map { it.className to it.methodName })) true
            else original
        }
        val update = HookRuntime.hook(methods.getValue(t.update), "manage_notifications/user-channel-update") { chain ->
            val channel = chain.args[2] as? NotificationChannel
            val allow = ready.get() && NotificationManagementPolicy.userChannelWrite(enabled(settings),
                chain.args[3] as Boolean, chain.args[4] as Int, chain.args[5] as Boolean, chain.args[1] as Int)
            // Do not rewrite setBlockable, XML persistence, equality, app-created channels or listener updates.
            scope.within(channel?.id?.takeIf { allow }) { chain.proceed() }
        }
        ready.set(query && update)
        return if (ready.get()) 2 else 0
    }
}
