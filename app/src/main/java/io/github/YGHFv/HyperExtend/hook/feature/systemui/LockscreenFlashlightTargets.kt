/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenFlashlightTargets {
    const val CONTROLLER = "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl"
    const val LISTENER = "com.android.systemui.statusbar.policy.FlashlightController\$FlashlightListener"
    val touch = SystemUiMethod(LockscreenShortcutTargets.MOVE, "onTouchEvent", "boolean", listOf("android.view.MotionEvent"))
    val intercept = SystemUiMethod(LockscreenShortcutTargets.MOVE, "interceptTouchEvent", "boolean")
    val interactive = SystemUiMethod(LockscreenShortcutTargets.MANAGER, "getInteractive", "boolean")
    val occluded = SystemUiMethod(LockscreenShortcutTargets.MANAGER, "isOccluded", "boolean")
    val goingAway = SystemUiMethod(LockscreenShortcutTargets.MANAGER, "isKeyguardGoingAway", "boolean")
    val showing = SystemUiMethod(LockscreenShortcutTargets.MANAGER, "isKeyguardShowing", "boolean")
    val talkback = SystemUiMethod(LockscreenShortcutTargets.MANAGER, "getTalkbackEnabled", "boolean")
    val pluginQueries = listOf(intercept, interactive, occluded, goingAway, showing, talkback)
    val callers = listOf(SystemUiMethod("com.miui.keyguard.shortcuts.ShortcutPluginImpl", "onTouchEvent", "boolean", listOf("android.view.MotionEvent")))
    val hostCallers = listOf(SystemUiMethod("com.android.keyguard.injector.KeyguardPanelViewInjector", "onTouchEvent", "boolean",
        listOf("android.view.MotionEvent", "int", "float", "float", "boolean", "boolean", "boolean")))
    val enabled = SystemUiMethod(CONTROLLER, "isEnabled", "boolean")
    val available = SystemUiMethod(CONTROLLER, "isAvailable", "boolean")
    val set = SystemUiMethod(CONTROLLER, "setFlashlight", "void", listOf("boolean"))
    val add = SystemUiMethod(CONTROLLER, "addCallback", "void", listOf("java.lang.Object"))
    val remove = SystemUiMethod(CONTROLLER, "removeCallback", "void", listOf("java.lang.Object"))
    val queries = listOf(enabled, available, set, add, remove,
        SystemUiMethod("dagger.Lazy", "get", "java.lang.Object"))
}
