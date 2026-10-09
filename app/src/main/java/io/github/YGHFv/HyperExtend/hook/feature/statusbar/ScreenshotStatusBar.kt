/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import android.graphics.Rect
import android.os.Build
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

/** Exclude the native StatusBar layer from this capture, never hide a live view. */
internal object ScreenshotStatusBar {
    fun installScreenshot(loader: ClassLoader): Int {
        if (Build.VERSION.SDK_INT < 34) return 0
        // Both OS4 capture strategies use this vendor API. Do not install on legacy
        // strategies that silently ignore exclusion names.
        val supported = listOf(
            "android.window.ScreenCaptureInternal\$CaptureArgs\$Builder",
            "android.window.ScreenCapture\$CaptureArgs\$Builder",
        ).any { name ->
            Reflect.loadClass(loader, name)?.let {
                Reflect.firstMethod(it, "setExcludeOrIncludeLayerNames") { method ->
                    method.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
                }
            } != null
        }
        if (!supported) {
            ModuleLog.warn("status_bar_screenshot_hide: native layer exclusion unavailable; capture unchanged")
            return 0
        }
        val type = Reflect.loadClass(loader, "com.miui.screenshot.core.util.DisplayCapture") ?: return 0
        val capture = Reflect.findMethod(type, "captureDisplay", Context::class.java,
            Int::class.javaPrimitiveType!!, Rect::class.java, Array<String>::class.java) ?: return 0
        val rear = Reflect.findMethod(type, "getRearDisplayId") ?: return 0
        rear.isAccessible = true
        val instance = Reflect.findField(type, "INSTANCE") ?: return 0
        type.declaredMethods.filter { it != capture }.forEach { HookRuntime.deoptimize(it, "screenshot/exclusionCaller") }
        // The rear-screen branch deliberately replaces all exclude names. Leave it native.
        return if (HookRuntime.hook(capture, "status_bar_screenshot_hide/excludeLayer") { chain ->
                val rearId = rear.invoke(instance.get(null)) as Int
                val displayId = chain.args[1] as Int
                val args = chain.args.toTypedArray()
                @Suppress("UNCHECKED_CAST")
                val excluded = args[3] as? Array<String>
                args[3] = ScreenshotLayerPolicy.exclusions(excluded, displayId == rearId)
                chain.proceed(args)
            }) 1 else 0
    }
}
