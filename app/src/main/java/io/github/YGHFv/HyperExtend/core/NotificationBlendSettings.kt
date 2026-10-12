/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object NotificationBlendSettings {
    const val FEATURE = "notification_blend_colors"
    const val KEYGUARD = "$FEATURE.keyguard"
    val options = listOf(HyperOption(KEYGUARD, "增强优化（锁屏）",
        "同时调整主屏锁屏通知和媒体混色；不改变锁屏内容与隐私限制", defaultEnabled = false))
}
