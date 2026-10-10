/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenChargingTargets {
    const val CONTROLLER = "com.android.systemui.statusbar.KeyguardIndicationController"
    const val ROTATE = "com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController"
    const val INJECTOR = "com.android.keyguard.injector.KeyguardIndicationInjector"
    val area = SystemUiMethod(CONTROLLER, "setIndicationArea", "void", listOf("android.view.ViewGroup"))
    val callers = listOf(
        SystemUiMethod("com.android.systemui.keyguard.KeyguardViewConfigurator", "start", "void"),
        SystemUiMethod("com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder", "bind",
            "com.android.systemui.util.kotlin.DisposableHandles", listOf("android.view.ViewGroup",
                "com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel", CONTROLLER), isStatic = true),
        SystemUiMethod("com.android.systemui.keyguard.ui.binder.KeyguardIndicationAreaBinder\$bind\$1", "dispose", "void"),
    )
}
