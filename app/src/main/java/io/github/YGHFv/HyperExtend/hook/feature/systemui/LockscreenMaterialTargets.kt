/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenMaterialTargets {
    const val MANAGER = "com.miui.keyguard.shortcuts.manager.MiuiShortcutManager"
    val hooks = listOf(LockscreenShortcutTargets.update, LockscreenShortcutTargets.release)
    val caller = LockscreenShortcutTargets.callers[1]
    val releaseCallers = listOf("inflateShortcutViews", "onDestroy")
        .map { SystemUiMethod("com.miui.keyguard.shortcuts.ShortcutPluginImpl", it, "void") }
    val queries = listOf(LockscreenFlashlightTargets.interactive, LockscreenFlashlightTargets.showing,
        LockscreenFlashlightTargets.occluded, LockscreenFlashlightTargets.goingAway)
    val constructorParameters = listOf("android.content.Context", "android.content.Context", "android.view.ViewGroup",
        "boolean", "boolean", LockscreenShortcutTargets.VIEW + "\$ShortcutTipsAnimCallback")
}
