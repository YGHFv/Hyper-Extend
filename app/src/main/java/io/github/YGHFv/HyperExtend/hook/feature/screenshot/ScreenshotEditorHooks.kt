/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

import android.os.Build
import io.github.YGHFv.HyperExtend.core.ScreenshotEditorSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks

internal object ScreenshotEditorHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var installed = 0
        if (settings.isOn(ScreenshotEditorSettings.PRIVACY_FEATURE)) {
            installed += installPrivacyMarking(loader, settings)
        }
        if (settings.isOn(ScreenshotEditorSettings.DEVICE_FEATURE)) {
            installed += installShellDevice(loader, settings)
        }
        return installed
    }

    private fun installPrivacyMarking(loader: ClassLoader, settings: HookSettings): Int {
        val method = NotificationHooks.resolve(loader, ScreenshotEditorTargets.privacyMarking) ?: return 0
        return if (HookRuntime.hook(method, "screenshot_editor/privacyMarking") { chain ->
                if (settings.isOn(ScreenshotEditorSettings.PRIVACY_FEATURE)) true else chain.proceed()
            }) 1 else 0
    }

    private fun installShellDevice(loader: ClassLoader, settings: HookSettings): Int {
        // 同时确认公开入口与字符串重载存在；后者是唯一能安全替换宿主
        // Build.DEVICE 查询键的位置，避免修改进程级静态字段。
        NotificationHooks.resolve(loader, ScreenshotEditorTargets.shellEntry) ?: return 0
        val query = NotificationHooks.resolve(loader, ScreenshotEditorTargets.shellQuery) ?: return 0
        return if (HookRuntime.hook(query, "screenshot_editor/shellDevice") { chain ->
                if (!settings.isOn(ScreenshotEditorSettings.DEVICE_FEATURE)) return@hook chain.proceed()
                val custom = ScreenshotEditorSettings.device(
                    settings.string(ScreenshotEditorSettings.DEVICE),
                ) ?: return@hook chain.proceed()
                val original = chain.args[0] as? String ?: return@hook chain.proceed()
                val replacement = ScreenshotEditorPolicy.remapShellQuery(original, Build.DEVICE, custom)
                if (replacement == original) chain.proceed() else chain.proceed(arrayOf(replacement))
            }) 1 else 0
    }
}
