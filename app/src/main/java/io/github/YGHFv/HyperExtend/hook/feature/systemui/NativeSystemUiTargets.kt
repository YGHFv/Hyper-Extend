/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NativeSystemUiTargets {
    const val VOLUME = "com.android.systemui.volume.VolumeDialogComponent"
    const val POWER = "com.android.systemui.globalactions.GlobalActionsComponent"
    const val EXTENSION = "com.android.systemui.statusbar.policy.ExtensionControllerImpl"
    const val BUILDER = "$EXTENSION\$ExtensionBuilder"
    const val IMPL = "$EXTENSION\$ExtensionImpl"
    val register = SystemUiMethod(BUILDER, "withPlugin", "void", listOf("java.lang.Class"))
    val start = SystemUiMethod(POWER, "start", "void")
    val create = SystemUiMethod(EXTENSION, "newExtension\$1", BUILDER)
    val default = SystemUiMethod(BUILDER, "withDefault", "void", listOf("java.util.function.Supplier"))
    val build = SystemUiMethod(BUILDER, "build", IMPL)
    val volumeConstructor = listOf("android.content.Context",
        "com.android.systemui.keyguard.KeyguardViewMediator", "com.android.systemui.plugins.ActivityStarter",
        "com.android.systemui.volume.VolumeDialogControllerImpl", "com.android.systemui.demomode.DemoModeController",
        "com.android.systemui.plugins.PluginDependencyProvider", EXTENSION, "com.android.systemui.tuner.TunerService",
        "com.android.systemui.volume.dialog.VolumeDialogPlugin")
    val fields = listOf(
        Triple(BUILDER, "mExtension", IMPL),
        Triple(IMPL, "this\$0", EXTENSION),
        Triple(EXTENSION, "mDefaultContext", "android.content.Context"),
    )
    val methods = listOf(register, start, create, default, build)
}
