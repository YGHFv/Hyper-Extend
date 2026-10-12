/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.view.View
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.QsLabelSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost

/**
 * 「隐藏快捷开关标签」—— 见 [QsLabelSettings] 的裁定说明。
 *
 * 挂点选在 `MiuiQSTileView#handleStateChanged(QSTile$State)` **之后**：原生刚刚写完标签文本、
 * 无障碍重要度、内容描述与可点击性，此时把 `mLabelContainer` 置为 `GONE` 只影响：
 * - 文字的测量/布局与绘制；
 * - 因为 `mLabelContainer` 在原生代码里就是那一段的父 ViewGroup，磁贴图标不受影响。
 *
 * 不修改任何 State 字段、不改文本、不动 contentDescription、不碰点击/长按/无障碍委托，
 * 也不缓存 View（每次走 host 字段），因此磁贴回收复用后不会串状态。
 */
internal object QsLabelHooks {
    private const val OWNER = "com.android.systemui.qs.tileimpl.MiuiQSTileView"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val owner = runCatching { Class.forName(OWNER, false, loader) }.getOrNull() ?: run {
            ModuleLog.warn("${QsLabelSettings.FEATURE}: $OWNER not found")
            return 0
        }
        val container = StatusIconHost.field(loader, OWNER, "mLabelContainer", "android.view.ViewGroup")
        val method = owner.declaredMethods.firstOrNull {
            it.name == "handleStateChanged" && it.parameterTypes.size == 1
        } ?: return 0
        if (!HookRuntime.deoptimize(method, "${QsLabelSettings.FEATURE}/tile-view")) return 0
        return if (HookRuntime.hookAfter(method, "${QsLabelSettings.FEATURE}/handleStateChanged") { chain, original ->
                // 功能关闭或安全模式触发时不改变可见性：原生本来就不写它，保持宿主原样。
                if (!SafeModeRuntime.blocked && settings.isOn(QsLabelSettings.FEATURE)) {
                    runCatching {
                        (container.get(chain.thisObject) as? View)?.visibility = View.GONE
                    }.onFailure { ModuleLog.error("${QsLabelSettings.FEATURE}: label visibility failed", it) }
                }
                original
            }) 1 else 0
    }
}
