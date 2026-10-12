/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object MobileMarkerTargets {
    const val BINDER = "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"
    const val GROUP = "$BINDER\$bind\$1\$1\$11\$1"
    const val IMAGE = "$BINDER\$bind\$1\$1\$13\$1"
    val emitters = listOf(GROUP, IMAGE).map {
        SystemUiMethod(it, "emit", "java.lang.Object", listOf("java.lang.Object", "kotlin.coroutines.Continuation"), false)
    }
    val collectors = listOf(11, 12, 13, 14, 15, 16, 17, 19, 20).map {
        SystemUiMethod("$BINDER\$bind\$1\$1\$$it", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"), false)
    }
    val fields = listOf(
        Triple(GROUP, "\$r8\$classId", "int"),
        Triple(GROUP, "\$voWifi", "android.widget.ImageView"),
        Triple(IMAGE, "\$r8\$classId", "int"),
        Triple(IMAGE, "\$satellite", "android.widget.ImageView"),
    )
    val pairAccess = listOf("getFirst", "getSecond").map {
        SystemUiMethod("kotlin.Pair", it, "java.lang.Object", emptyList(), false)
    }
}
