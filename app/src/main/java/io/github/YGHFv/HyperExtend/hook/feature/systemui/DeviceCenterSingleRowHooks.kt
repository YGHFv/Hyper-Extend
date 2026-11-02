/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.os.Looper
import io.github.YGHFv.HyperExtend.core.DeviceCenterSingleRowSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

internal object DeviceCenterSingleRowHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val id = DeviceCenterSingleRowSettings.FEATURE
        val targets = DeviceCenterSingleRowTargets.resolve(loader)
        // 两版宿主都先把新列表赋给控制器，再构造差量回调；必须防止构造器被此调用者内联。
        if (!targets.callers.map { HookRuntime.deoptimize(it, "$id/差量调用者") }.all { it }) return 0
        return if (HookRuntime.hook(targets.constructor, "$id/单行显示列表") { chain ->
            if (!DeviceCenterSingleRowPolicy.eligible(settings.isOn(id), SafeModeRuntime.blocked,
                    Looper.myLooper() == Looper.getMainLooper())) return@hook chain.proceed()
            @Suppress("UNCHECKED_CAST")
            val items = chain.args[1] as? ArrayList<Any?> ?: return@hook chain.proceed()
            if (items === chain.args[0]) return@hook chain.proceed()
            val selected = DeviceCenterSingleRowPolicy.select(items,
                targets.device::isInstance, targets.detail::isInstance)
            if (selected !== items) {
                // 原地整理以保持控制器、差量回调、适配器共用同一列表；旧列表与完整设备数据不动。
                items.clear()
                items.addAll(selected)
            }
            chain.proceed()
        }) 1 else 0
    }
}
