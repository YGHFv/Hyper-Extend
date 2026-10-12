/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object WifiStandardTargets {
    const val MAP = "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModelInject\$special\$\$inlined\$map\$1"
    const val MAPPER = "$MAP\$2"
    const val COLLECTOR = "kotlinx.coroutines.flow.FlowCollector"
    val emit = SystemUiMethod(MAPPER, "emit", "java.lang.Object",
        listOf("java.lang.Object", "kotlin.coroutines.Continuation"), false)
    val delegateEmit = emit.copy(owner = COLLECTOR)
    val collect = SystemUiMethod(MAP, "collect", "java.lang.Object",
        listOf(COLLECTOR, "kotlin.coroutines.Continuation"), false)
    val resume = SystemUiMethod(
        "com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModelInject\$special\$\$inlined\$map\$3\$2\$1",
        "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"), false)
    val fields = listOf(
        Triple(MAPPER, "\$r8\$classId", "int"),
        Triple(MAPPER, "\$this_unsafeFlow", COLLECTOR),
    )
}
