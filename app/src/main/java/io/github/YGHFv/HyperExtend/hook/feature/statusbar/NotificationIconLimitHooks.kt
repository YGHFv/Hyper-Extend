/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

internal object NotificationIconLimitHooks {
    private const val OPTION = "status_bar_icons.notification_icon_max"
    private const val VALUE = "status_bar_icons.notification_icon_max_value"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(OPTION)) return 0
        val t = NotificationIconLimitTargets
        val methods = (listOf(t.emit, t.collection) + t.sourceBindings + t.nativeLayout).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        val data = Class.forName(t.DATA, false, loader)
        val limitType = Class.forName("${t.DATA}\$LimitType", false, loader)
        val constructor = data.getDeclaredConstructor(List::class.java, Int::class.javaPrimitiveType, limitType)
            .apply { isAccessible = true }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$OPTION/caller") }) return 0
        val host = StatusIconHost()
        return if (HookRuntime.hook(methods.getValue(t.emit), "$OPTION/emit") { chain ->
            val view = fields[0].get(chain.thisObject) as? View ?: return@hook chain.proceed()
            val now = HookRuntime.settings()
            if (!now.isOn(OPTION) || !host.eligible(view) ||
                fields[1].get(chain.thisObject) != "statusbar[0]" ||
                fields[2].get(view) != "statusbar[0]") return@hook chain.proceed()
            val original = chain.args[0]?.takeIf { it.javaClass == data } ?: return@hook chain.proceed()
            val icons = fields[3].get(original) as? List<*> ?: return@hook chain.proceed()
            val nativeLimit = fields[4].getInt(original)
            val type = fields[5].get(original) as? Enum<*> ?: return@hook chain.proceed()
            val requested = StatusIconPolicy.notificationLimit(nativeLimit, icons.size,
                type.name, now.number(VALUE, 3, 1, 15)) ?: return@hook chain.proceed()
            if (requested == nativeLimit) return@hook chain.proceed()
            val replacement = constructor.newInstance(icons, requested, type)
            // Copy before computeDifference/binding failure accounting, not after setMaxIconsAmount.
            // Keep icon filtering/order, MaximumIndex vs MaximumAmount, and native geometry.
            chain.proceed(arrayOf(replacement, chain.args[1]))
        }) 1 else 0
    }
}
