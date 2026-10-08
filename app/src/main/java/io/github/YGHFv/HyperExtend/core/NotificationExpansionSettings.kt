/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object NotificationExpansionSettings {
    const val EXPAND = "notification_auto_expand"
    const val PACKAGES = "$EXPAND.packages"
    const val COLLAPSE = "notification_expanded_timeout"
    val timeout = HyperSlider("$COLLAPSE.delay", "展开悬浮通知停留时间", 10, 150, 45, divisor = 10, unit = " 秒")
}
