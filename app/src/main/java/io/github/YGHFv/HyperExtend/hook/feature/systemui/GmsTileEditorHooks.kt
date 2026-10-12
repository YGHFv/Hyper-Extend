/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.os.Looper
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost

/** Editor candidates only. Never writes the user's saved tile list. */
internal object GmsTileEditorHooks {
    private fun enabled() = GmsTileHooks.ready && !SafeModeRuntime.blocked && HookRuntime.settings().isOn(GmsTilePolicy.FEATURE)
    private fun supported(context: Context, plugin: Boolean) =
        context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L &&
            (!plugin || context.packageManager.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode == 183022200L)
    fun installClassic(loader: ClassLoader): Int {
        if (!GmsTileHooks.ready) return 0
        val t = StockTilesTargets
        val methods = t.classicMethods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val fields = t.classicFields.associate { (owner, name, type) -> name to StatusIconHost.field(loader, owner, name, type) }
        if (!methods.values.all { HookRuntime.deoptimize(it, "gms_tile/classic-caller") }) return 0
        val scope = StockTilesScope()
        var ready = false
        val read = HookRuntime.hookAfter(Context::class.java.getDeclaredMethod("getString", Int::class.javaPrimitiveType),
            "gms_tile/classic-stock") { chain, result ->
            if (ready && enabled() && scope.take(chain.thisObject, chain.args[0] as Int)) GmsTilePolicy.append(result as? String) else result
        }
        val show = HookRuntime.hook(methods.getValue(t.show), "gms_tile/classic-editor") { chain ->
            val frame = if (ready && enabled() && Looper.myLooper() == Looper.getMainLooper()) runCatching {
                val query = fields.getValue("tileQueryHelper").get(chain.thisObject)
                val context = fields.getValue("mContext").get(query) as Context
                if (!supported(context, false) || context.resources.getResourceName(t.STOCK_RESOURCE) !=
                    "com.android.systemui:string/miui_quick_settings_tiles_stock") null
                else StockTilesScope.Frame(context, t.STOCK_RESOURCE)
            }.getOrNull() else null
            scope.withFrame(frame) { chain.proceed() }
        }
        ready = read && show
        return if (ready) 2 else 0
    }
    fun installPlugin(loader: ClassLoader): Int {
        if (!GmsTileHooks.ready) return 0
        val t = StockTilesTargets
        val methods = (t.pluginMethods + GmsTileTargets.pluginCallers).associateWith {
            requireNotNull(NotificationHooks.resolve(loader, it))
        }
        val context = StatusIconHost.field(loader, t.PLUGIN_QUERY, "context", "android.content.Context")
        if (!methods.values.all { HookRuntime.deoptimize(it, "gms_tile/plugin-caller") }) return 0
        val scope = StockTilesScope()
        var ready = false
        val read = HookRuntime.hookAfter(methods.getValue(t.pluginStock), "gms_tile/plugin-stock") { chain, result ->
            if (ready && enabled() && scope.take(chain.thisObject, 0)) GmsTilePolicy.append(result as? String) else result
        }
        val add = HookRuntime.hook(methods.getValue(t.pluginAdd), "gms_tile/plugin-editor") { chain ->
            val frame = if (ready && enabled() && runCatching {
                supported(context.get(chain.thisObject) as Context, true)
            }.getOrDefault(false)) chain.thisObject?.let { StockTilesScope.Frame(it, 0) } else null
            scope.withFrame(frame) { chain.proceed() }
        }
        ready = read && add
        return if (ready) 2 else 0
    }
}
