/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookSettings

internal object StatusBarIcons {
    fun install(loader: ClassLoader, settings: HookSettings): Int = listOf(
        "slots" to { IconBlockListHooks.install(loader, settings) },
        "battery" to { BatteryVisibilityHooks.install(loader, settings) },
        "notifications" to { NotificationIconLimitHooks.install(loader, settings) },
        "wifi" to { WifiIndicatorHooks.install(loader, settings) },
        "wifi-standard" to { WifiStandardHooks.install(loader, settings) },
        "alarm-window" to { AlarmWindowHooks.install(loader, settings) },
    ).sumOf { (name, install) ->
        runCatching(install).getOrElse {
            ModuleLog.error("status_bar_icons/$name installation failed; leaving native behavior", it)
            0
        }
    }
}
