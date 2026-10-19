/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.os.Looper
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.concurrent.atomic.AtomicBoolean

internal object StockTilesHooks {
    private const val FEATURE = StockTilesPolicy.FEATURE

    private fun checkFactory(loader: ClassLoader) {
        val factory = Class.forName(StockTilesTargets.FACTORY, false, loader)
        for (name in StockTilesTargets.providers.values) {
            require(factory.getDeclaredField(name).type.name == "javax.inject.Provider")
        }
    }

    fun installClassic(loader: ClassLoader, settings: HookSettings): Int {
        checkFactory(loader)
        val targets = StockTilesTargets
        val methods = targets.classicMethods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = targets.classicFields.associate { (owner, name, type) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(this.type.name == type); isAccessible = true
            }
        }
        // Context.getString is final; deopt the editor and both callers of its inlined body.
        if (!methods.values.map { HookRuntime.deoptimize(it, "$FEATURE/classicCaller") }.all { it }) return 0
        val scope = StockTilesScope()
        val ready = AtomicBoolean(false)
        val logged = AtomicBoolean(false)
        val read = HookRuntime.hookAfter(Context::class.java.getDeclaredMethod("getString", Int::class.javaPrimitiveType),
            "$FEATURE/classicRead") { chain, original ->
            if (ready.get() && scope.take(chain.thisObject, chain.args[0] as Int) && settings.isOn(FEATURE)) {
                // 只有「独立磁贴」两档才把 5G spec 放进候选列表；选了「内置移动网络磁贴」
                // 就不新增磁贴（那条路径改为往原生详情页注入）。
                val includeFiveG = FiveGTilePolicy.includeInCandidates(
                    settings.string(FiveGTilePolicy.STYLE), FiveGTileHooks.ready,
                )
                val result = StockTilesPolicy.append(original as? String, includeFiveG)
                if (result != original && logged.compareAndSet(false, true))
                    ModuleLog.info("$FEATURE: classic editor candidates reached; fiveGFactoryReady=${FiveGTileHooks.ready}; fiveGIncluded=${FiveGTilePolicy.SPEC in result.orEmpty().split(',')} (device acceptance pending)")
                result ?: original
            } else original
        }
        val show = HookRuntime.hook(methods.getValue(targets.show), "$FEATURE/classicEditor") { chain ->
            scope.withFrame(null) {
                val frame = if (ready.get() && settings.isOn(FEATURE) && Looper.myLooper() == Looper.getMainLooper()) {
                    runCatching {
                        val helper = fields.getValue("tileQueryHelper").get(chain.thisObject) ?: return@runCatching null
                        val context = fields.getValue("mContext").get(helper) as? Context ?: return@runCatching null
                        if (context.resources.getResourceName(targets.STOCK_RESOURCE) !=
                            "com.android.systemui:string/miui_quick_settings_tiles_stock") return@runCatching null
                        StockTilesScope.Frame(context, targets.STOCK_RESOURCE)
                    }.getOrNull()
                } else null
                scope.withFrame(frame) { chain.proceed() }
            }
        }
        ready.set(read && show)
        return if (ready.get()) 2 else 0
    }

    fun installPlugin(loader: ClassLoader, hostLoader: ClassLoader, settings: HookSettings): Int {
        checkFactory(hostLoader)
        val fiveGReady = FiveGTileHooks.ready && runCatching { FiveGTileHooks.preparePlugin(loader) }.getOrDefault(false)
        ModuleLog.info("$FEATURE: plugin 5G readiness=$fiveGReady; factoryReady=${FiveGTileHooks.ready}")
        val targets = StockTilesTargets
        val methods = targets.pluginMethods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val contextField = methods.getValue(targets.pluginAdd).declaringClass.getDeclaredField("context").apply {
            require(type == Context::class.java); isAccessible = true
        }
        if (!methods.values.map { HookRuntime.deoptimize(it, "$FEATURE/pluginCaller") }.all { it }) return 0
        val scope = StockTilesScope()
        val ready = AtomicBoolean(false)
        val logged = AtomicBoolean(false)
        val read = HookRuntime.hookAfter(methods.getValue(targets.pluginStock), "$FEATURE/pluginRead") { chain, original ->
            if (ready.get() && scope.take(chain.thisObject, 0) && settings.isOn(FEATURE)) {
                val includeFiveG = FiveGTilePolicy.includeInCandidates(
                    settings.string(FiveGTilePolicy.STYLE), fiveGReady,
                )
                val result = StockTilesPolicy.append(original as? String, includeFiveG)
                if (result != original && logged.compareAndSet(false, true))
                    ModuleLog.info("$FEATURE: plugin editor candidates reached; fiveGFactoryReady=$fiveGReady; fiveGIncluded=${FiveGTilePolicy.SPEC in result.orEmpty().split(',')} (device acceptance pending)")
                result ?: original
            } else original
        }
        // This method runs on the host's worker. Scope the actual work, not queryTiles' enqueue.
        val add = HookRuntime.hook(methods.getValue(targets.pluginAdd), "$FEATURE/pluginEditor") { chain ->
            scope.withFrame(null) {
                val context = contextField.get(chain.thisObject) as? Context
                val frame = if (ready.get() && settings.isOn(FEATURE) && context != null)
                    StockTilesScope.Frame(chain.thisObject!!, 0) else null
                scope.withFrame(frame) { chain.proceed() }
            }
        }
        ready.set(read && add)
        return if (ready.get()) 2 else 0
    }
}
