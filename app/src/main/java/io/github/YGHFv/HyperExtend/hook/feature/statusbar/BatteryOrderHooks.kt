/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.view.View
import android.view.ViewGroup
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

internal object BatteryOrderHooks {
    fun install(loader: ClassLoader): Int {
        val t = BatteryOrderTargets
        val layout = NotificationHooks.resolve(loader, t.layout) ?: return 0
        val fields = t.fields.associate { (name, type) -> name to StatusIconHost.field(loader, t.OWNER, name, type) }
        if (!HookRuntime.deoptimize(layout, "status_bar_battery_style/order-layout")) return 0
        val host = StatusIconHost()
        return if (HookRuntime.hookAfter(layout, "status_bar_battery_style/order-layout") { chain, result ->
            val root = chain.thisObject as? ViewGroup ?: return@hookAfter result
            val settings = HookRuntime.settings()
            if (!host.eligible(root) || !settings.isOn("status_bar_battery_style.change_location") ||
                settings.isOn("status_bar_icons.battery_icon") || settings.isOn("status_bar_icons.battery_percent") ||
                fields.getValue("mBatteryStyle").getInt(root) != 3 ||
                fields.getValue("mLayoutFromTag").getInt(root) != 0 ||
                fields.getValue("mStatusBarState").getInt(root) != 0 ||
                t.fields.filter { it.second == "boolean" }.any { fields.getValue(it.first).getBoolean(root) }) return@hookAfter result
            val icon = fields.getValue("mBatteryDigitalView").get(root) as? View ?: return@hookAfter result
            val percent = fields.getValue("mBatteryPercentContainer").get(root) as? View ?: return@hookAfter result
            val charge = fields.getValue("mBatteryChargingView").get(root) as? View ?: return@hookAfter result
            if (icon.parent !== root || percent.parent !== root || charge.visibility == View.VISIBLE ||
                listOf(icon, percent).any { it.visibility != View.VISIBLE || it.alpha != 1f ||
                    it.scaleX != 1f || it.scaleY != 1f || it.translationX != 0f || it.translationY != 0f }) return@hookAfter result
            val next = BatteryPositionPolicy.swap(root.width, icon.left, icon.right, percent.left, percent.right,
                root.layoutDirection == View.LAYOUT_DIRECTION_RTL) ?: return@hookAfter result
            // Current onLayout addresses fields, not child order. Keep native sizes, vertical
            // bounds, parentage, accessibility identity and charging/animation ownership.
            icon.layout(next.first, icon.top, next.first + icon.width, icon.bottom)
            percent.layout(next.second, percent.top, next.second + percent.width, percent.bottom)
            result
        }) 1 else 0
    }
}

internal object BatteryPositionPolicy {
    fun swap(parentWidth: Int, iconLeft: Int, iconRight: Int, textLeft: Int, textRight: Int,
        rtl: Boolean): Pair<Int, Int>? {
        if (parentWidth <= 0 || iconLeft < 0 || textLeft < 0 || iconRight > parentWidth ||
            textRight > parentWidth || iconRight <= iconLeft || textRight <= textLeft) return null
        if (if (rtl) textRight > iconLeft else iconRight > textLeft) return null
        val sum = minOf(iconLeft, textLeft).toLong() + maxOf(iconRight, textRight)
        return (sum - iconRight).toInt() to (sum - textRight).toInt()
    }
}
