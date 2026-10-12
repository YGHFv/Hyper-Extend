/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object OperatorIconTargets {
    const val OWNER = "com.android.systemui.MiuiOperatorCustomizedPolicy"
    const val CONFIG = "com.miui.interfaces.IOperatorCustomizedPolicy\$OperatorConfig"
    val create = SystemUiMethod(OWNER, "getMiuiOperatorConfig", CONFIG, listOf("int"), false)
    val callers = listOf(
        SystemUiMethod(OWNER, "updateMiuiOperatorConfig", "void", listOf("int"), false),
        SystemUiMethod("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl\$special\$\$inlined\$flatMapLatest\$2",
            "invokeSuspend", "java.lang.Object", listOf("java.lang.Object"), false),
        SystemUiMethod("com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconsInteractorImpl",
            "getMiuiMobileConnectionInterForSubId",
            "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl",
            listOf("int", "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconInteractor"), false),
        SystemUiMethod("com.android.systemui.statusbar.policy.HDController", "isVisible", "boolean", listOf("int"), false),
    )
    val fields = listOf(
        Triple(OWNER, "mContext", "android.content.Context"),
        Triple(CONFIG, "hideVolte", "boolean"),
    )
}
