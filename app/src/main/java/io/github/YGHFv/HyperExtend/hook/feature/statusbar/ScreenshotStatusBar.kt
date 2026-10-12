/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

/** Exclude the native StatusBar layer from this capture, never hide a live view. */
internal object ScreenshotStatusBar {
    fun installScreenshot(loader: ClassLoader): Int {
        val t = ScreenshotCaptureTargets
        val capture = NotificationHooks.resolve(loader, t.capture) ?: return 0
        val caller = NotificationHooks.resolve(loader, t.caller) ?: return 0
        // Resolve only: never construct a capture builder, initialize the strategy or read services.
        NotificationHooks.resolve(loader, t.exclude) ?: return 0
        NotificationHooks.resolve(loader, t.mode) ?: return 0
        if (!listOf(capture, caller).all { HookRuntime.deoptimize(it, "status_bar_screenshot_hide/capture") }) return 0
        return if (HookRuntime.hook(capture, "status_bar_screenshot_hide/native-strategy") { chain ->
            val context = chain.args[0] as? Context ?: return@hook chain.proceed()
            val version = context.packageManager.getPackageInfo("com.miui.screenshot", 0).compatibleVersionCode
            val display = chain.args[1] as? Int ?: return@hook chain.proceed()
            @Suppress("UNCHECKED_CAST")
            val original = chain.args[3] as? Array<String> ?: return@hook chain.proceed()
            if (!ScreenshotLayerPolicy.eligible(version, display, HookRuntime.settings().isOn("status_bar_screenshot_hide"),
                    original.toList())) return@hook chain.proceed()
            val replacement = ScreenshotLayerPolicy.exclusions(original, false)
            if (replacement === original) return@hook chain.proceed()
            val args = chain.args.toTypedArray()
            args[3] = replacement
            chain.proceed(args)
        }) 1 else 0
    }
}
