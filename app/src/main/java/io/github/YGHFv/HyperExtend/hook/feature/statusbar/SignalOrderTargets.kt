/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object SignalOrderTargets {
    const val LIST = "com.android.systemui.statusbar.phone.ui.StatusBarIconList"
    const val SLOT = "$LIST\$Slot"
    val factory = SystemUiMethod(
        "com.android.systemui.statusbar.dagger.CentralSurfacesDependenciesModule_ProvideStatusBarIconListFactory",
        "provideStatusBarIconList", LIST, listOf("android.content.Context"), true)
    val caller = SystemUiMethod(
        "com.android.systemui.dagger.DaggerReferenceGlobalRootComponent\$ReferenceSysUIComponentImpl\$SwitchingProvider",
        "get2", "java.lang.Object", emptyList(), false)
    val fields = listOf(
        Triple(LIST, "mSlots", "java.util.ArrayList"),
        Triple(LIST, "mViewOnlySlots", "java.util.List"),
        Triple(SLOT, "mName", "java.lang.String"),
        Triple(SLOT, "mHolder", "com.android.systemui.statusbar.phone.StatusBarIconHolder"),
        Triple(SLOT, "mSubSlots", "java.util.ArrayList"),
    )
}
