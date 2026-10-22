/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.app.ActivityManager
import android.content.Context
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 第三方主题 DRM 校验绕过。 */
internal object SystemFrameworkThemeHooks {
    private const val REAR_SCREEN_ACTIVITY = "com.rearScreen.RearScreenDetailActivity"
    private val bypassDrm = ThreadLocal<Boolean>()

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("system_framework_theme_provider")) return 0
        val drm = Reflect.loadClass(loader, "miui.drm.DrmManager")
        val receiver = Reflect.loadClass(loader, "miui.drm.ThemeReceiver")
        if (drm == null || receiver == null) {
            ModuleLog.warn("theme_provider: DrmManager or ThemeReceiver not found")
            return 0
        }

        val resultClass = Reflect.loadClass(loader, "miui.drm.DrmManager\$DrmResult")
        val success = resultClass?.let { Reflect.staticFieldOrNull(it, "DRM_SUCCESS") }
            ?.let { Reflect.attempt { it.get(null) } }
        if (success == null) {
            ModuleLog.warn("theme_provider: DrmManager.DRM_SUCCESS unavailable")
            return 0
        }

        var count = 0
        for (method in Reflect.findMethods(drm, "isLegal")) {
            if (method.returnType != resultClass) continue
            if (HookRuntime.hook(method, "theme_provider/DrmManager#isLegal") { chain ->
                    if (bypassDrm.get() == true) success else chain.proceed()
                }
            ) count++
        }

        val validate = Reflect.findMethods(receiver, "validateTheme", paramCount = 4).firstOrNull()
        if (validate != null &&
            HookRuntime.hook(validate, "theme_provider/ThemeReceiver#validateTheme") { chain ->
                if (isRearScreenActive(loader)) {
                    chain.proceed()
                } else {
                    bypassDrm.set(true)
                    try {
                        chain.proceed()
                    } finally {
                        bypassDrm.remove()
                    }
                }
            }
        ) count++
        return count
    }

    private fun isRearScreenActive(loader: ClassLoader): Boolean {
        val context = systemContext(loader) ?: return false
        @Suppress("DEPRECATION")
        val tasks = Reflect.attempt {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                ?.getRunningTasks(1)
        }.orEmpty()
        val top = tasks.firstOrNull()?.topActivity
        return top?.className == REAR_SCREEN_ACTIVITY
    }

    private fun systemContext(loader: ClassLoader): Context? {
        val threadClass = Reflect.loadClass(loader, "android.app.ActivityThread") ?: return null
        val current = Reflect.findMethods(threadClass, "currentActivityThread", paramCount = 0)
            .firstOrNull()
            ?.let { Reflect.attempt { it.invoke(null) } }
            ?: return null
        return Reflect.callWith(current, "getSystemContext") as? Context
    }
}
