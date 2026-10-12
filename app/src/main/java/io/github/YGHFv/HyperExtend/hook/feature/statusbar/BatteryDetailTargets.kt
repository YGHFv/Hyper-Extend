/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object BatteryDetailTargets {
    const val OWNER = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"
    val inflate = SystemUiMethod(OWNER, "onFinishInflate", "void", emptyList(), false)
    val fields = listOf(
        Triple(OWNER, "mStatusBarLeftContainer", "android.view.View"),
    )
    // The actual clock and parent identities are from this version's complete status_bar.xml.
    const val CLOCK = 0x7f0b027d
    const val LEFT = 0x7f0b090c
}
