/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenShortcutTargets {
    const val VIEW = "com.miui.keyguard.shortcuts.controller.ShortcutViewLayoutController"
    const val MOVE = "com.miui.keyguard.shortcuts.controller.ShortcutMoveController"
    const val MANAGER = "com.miui.keyguard.shortcuts.manager.ShortcutManager"
    const val ENTITY = "com.miui.keyguard.shortcuts.data.ShortcutEntity"
    val update = SystemUiMethod(VIEW, "updateShortcutView", "void", listOf(ENTITY, "android.widget.ImageView", "android.view.View"))
    val click = SystemUiMethod(VIEW, "handleOnIconClick", "void", listOf("boolean"))
    val release = SystemUiMethod(VIEW, "release", "void")
    val hit = SystemUiMethod(MOVE, "isTouchOnIcon", "boolean", listOf("android.view.View", "float", "float"))
    val launch = SystemUiMethod(MANAGER, "startShortcutActivity", "boolean")
    val selectedLeft = SystemUiMethod(MANAGER, "isOccludedMovedShortcutLeft", "boolean")
    val selectedRight = SystemUiMethod(MANAGER, "isOccludedMovedShortcutRight", "boolean")
    val hooks = listOf(update, click, release, hit, launch)
    val queries = listOf(selectedLeft, selectedRight)
    val callers = listOf(
        SystemUiMethod("com.miui.keyguard.shortcuts.ShortcutPluginImpl", "onDestroy", "void"),
        SystemUiMethod(VIEW, "onShortcutDataChanged", "void", listOf(ENTITY, "boolean")),
        SystemUiMethod(VIEW, "_init_\$lambda\$2", "void", listOf(VIEW, "android.view.View"), isStatic = true),
        SystemUiMethod(VIEW, "_init_\$lambda\$3", "void", listOf(VIEW, "android.view.View"), isStatic = true),
        SystemUiMethod(VIEW, "\$r8\$lambda\$gbgW4GChjSdmSVk91pzb3F1NSE4", "void", listOf(VIEW, "android.view.View"), isStatic = true),
        SystemUiMethod(VIEW, "\$r8\$lambda\$BWvT4TlAHxG4sOJIfi8A5yKB0tQ", "void", listOf(VIEW, "android.view.View"), isStatic = true),
        SystemUiMethod(VIEW + "\$\$ExternalSyntheticLambda0", "onClick", "void", listOf("android.view.View")),
        SystemUiMethod(VIEW + "\$\$ExternalSyntheticLambda1", "onClick", "void", listOf("android.view.View")),
        SystemUiMethod(MOVE, "initCommonTouchEvent", "void", listOf("android.view.MotionEvent")),
        SystemUiMethod(MOVE, "onTouchEvent", "boolean", listOf("android.view.MotionEvent")),
        SystemUiMethod("com.miui.keyguard.shortcuts.controller.ShortcutOccludedAnimController\$startShortcutActivity\$1",
            "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
    )
}
