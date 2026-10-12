/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object NotificationIconLimitTargets {
    const val BINDER = "com.android.systemui.statusbar.notification.icon.ui.viewbinder.NotificationIconContainerViewBinder"
    const val COLLECTOR = "$BINDER\$bindIcons\$5\$invokeSuspend\$\$inlined\$collectTracingEach\$1"
    const val DATA = "com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconsViewData"
    const val CONTAINER = "com.android.systemui.statusbar.phone.NotificationIconContainer"
    val emit = SystemUiMethod(COLLECTOR, "emit", "java.lang.Object",
        listOf("java.lang.Object", "kotlin.coroutines.Continuation"), false)
    val collection = SystemUiMethod("$BINDER\$bindIcons\$5", "invokeSuspend", "java.lang.Object",
        listOf("java.lang.Object"), false)
    val sourceBindings = listOf(2, 4).map {
        SystemUiMethod("$BINDER\$bind\$$it", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"), false)
    }
    val nativeLayout = listOf(
        SystemUiMethod(CONTAINER, "setMaxIconsAmount", "void", listOf("int"), false),
        SystemUiMethod(CONTAINER, "onMeasure", "void", listOf("int", "int"), false),
        SystemUiMethod(CONTAINER, "calculateIconXTranslations", "void", emptyList(), false),
    )
    val fields = listOf(
        Triple(COLLECTOR, "\$view\$inlined", CONTAINER),
        Triple(COLLECTOR, "\$logTag\$inlined", "java.lang.String"),
        Triple(CONTAINER, "mLogTag", "java.lang.String"),
        Triple(DATA, "visibleIcons", "java.util.List"),
        Triple(DATA, "iconLimit", "int"),
        Triple(DATA, "limitType", "$DATA\$LimitType"),
    )
}
