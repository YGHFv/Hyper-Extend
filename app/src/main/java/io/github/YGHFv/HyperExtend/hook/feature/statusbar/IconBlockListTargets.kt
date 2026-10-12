/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object IconBlockListTargets {
    const val MANAGER = "com.android.systemui.statusbar.phone.ui.IconManager"
    const val CONTAINER = "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
    const val DEFAULTS = "com.android.systemui.statusbar.phone.MiuiIconManagerUtils"
    val setBlockList = SystemUiMethod(MANAGER, "setBlockList", "void", listOf("java.util.List"), false)
    val addIgnored = SystemUiMethod(CONTAINER, "addIgnoredSlots", "void", listOf("java.util.List"), false)
    val callers = listOf(
        SystemUiMethod(CONTAINER, "setIgnoredSlots", "void", listOf("java.util.List"), false),
        SystemUiMethod("com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons", "onAttachedToWindow", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.controlcenter.phone.widget.ControlCenterFakeStatusIcons", "onFinishInflate", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon", "onAttachedToWindow", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.controlcenter.phone.widget.ControlCenterStatusBarIcon", "onFinishInflate", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.statusbar.phone.KeyguardStatusBarViewController", "onViewAttached", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.statusbar.pipeline.shared.ui.binder.HomeStatusBarIconBlockListBinder\$bind\$1\$1\$1",
            "emit", "java.lang.Object", listOf("java.lang.Object", "kotlin.coroutines.Continuation"), false),
        SystemUiMethod("com.android.systemui.qs.MiuiQSHeaderView", "onAttachedToWindow", "void", emptyList(), false),
        SystemUiMethod("com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", "updateCutoutLocation", "void", emptyList(), false),
    )
}
