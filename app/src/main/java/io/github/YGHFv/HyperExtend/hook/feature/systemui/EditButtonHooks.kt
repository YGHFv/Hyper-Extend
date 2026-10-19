/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings

internal object EditButtonHooks {
    const val FEATURE = "control_center_hide_edit"
    private const val PREFIX = "miui.systemui.controlcenter.panel.main."
    val available = SystemUiMethod(PREFIX + "qs.EditButtonController", "available", "boolean", listOf("boolean"))
    val distribute = SystemUiMethod(PREFIX + "MainPanelContentDistributor", "distributePanels", "void", listOf("boolean"))
    val context = SystemUiMethod("miui.systemui.util.ViewController", "getContext", "android.content.Context")
    val methods = listOf(available, distribute, context)

    fun visible(native: Boolean, enabled: Boolean): Boolean =
        native && !(enabled)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val resolved = methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!resolved.values.map { HookRuntime.deoptimize(it, "$FEATURE/caller") }.all { it }) return 0
        return if (HookRuntime.hookAfter(resolved.getValue(available), "$FEATURE/available") { chain, original ->
                if (original != true || !settings.isOn(FEATURE)) return@hookAfter original
                visible(true, true)
            }) 1 else 0
    }
}
