/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 显示与通知类系统框架功能。 */
internal object SystemFrameworkDisplayNotificationHooks {

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_screen_all_rotations")) {
            count += installAllRotations(loader)
        }
        if (settings.isOn("system_framework_quick_screenshot")) {
            count += installQuickScreenshot(loader)
        }
        if (settings.isOn("system_framework_disable_72h_verify")) {
            count += installNoOp(
                loader,
                "com.android.server.locksettings.LockSettingsStrongAuth",
                "rescheduleStrongAuthTimeoutAlarm",
                "system_framework_disable_72h_verify",
                Long::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }
        if (settings.isOn("system_framework_app_link_verify")) {
            count += installAppLinkVerify(loader)
        }
        if (settings.isOn("system_framework_untrusted_touch")) {
            count += installUntrustedTouch(loader)
        }
        if (settings.isOn("system_framework_delete_alert_window_notification")) {
            count += installNoOp(
                loader,
                "com.android.server.wm.AlertWindowNotification",
                "onPostNotification",
                "system_framework_delete_alert_window_notification",
            )
        }
        if (settings.isOn("system_framework_notification_channel_blockable")) {
            count += installNotificationChannel(loader)
        }
        if (settings.isOn("system_framework_disable_link_turbo_toast")) {
            count += installLinkTurboToast(loader)
        }
        return count
    }

    private fun installAllRotations(loader: ClassLoader): Int {
        var count = 0
        val policy = Reflect.loadClass(loader, "com.android.internal.view.RotationPolicy")
        val allowed = policy?.let {
            Reflect.findMethod(it, "areAllRotationsAllowed", Context::class.java)
        }
        if (allowed != null &&
            HookRuntime.hookReturning(allowed, "screen_all_rotations/RotationPolicy#areAllRotationsAllowed", true)
        ) {
            count++
        }

        val displayRotation = Reflect.loadClass(loader, "com.android.server.wm.DisplayRotation")
        if (displayRotation == null) {
            ModuleLog.warn("screen_all_rotations: DisplayRotation not found")
            return count
        }
        for (constructor in displayRotation.declaredConstructors) {
            if (HookRuntime.hook(constructor, "screen_all_rotations/DisplayRotation#<init>") { chain ->
                    val result = chain.proceed()
                    Reflect.writeField(chain.thisObject, "mAllowAllRotations", 1)
                    result
                }
            ) count++
        }
        return count
    }

    private fun installQuickScreenshot(loader: ClassLoader): Int {
        val keyGesture = Reflect.loadClass(loader, "com.android.server.input.KeyGestureController")
        val keyMethod = keyGesture?.let {
            Reflect.findMethods(it, "getScreenshotChordLongPressDelay", paramCount = 0).firstOrNull()
        }
        if (keyMethod != null) {
            return if (HookRuntime.hookReturning(
                    keyMethod,
                    "quick_screenshot/KeyGestureController#getScreenshotChordLongPressDelay",
                    0L,
                )
            ) 1 else 0
        }

        val legacy = Reflect.loadClass(loader, "com.android.server.policy.PhoneWindowManager")
        val legacyMethod = legacy?.let {
            Reflect.findMethods(it, "getScreenshotChordLongPressDelay", paramCount = 0).firstOrNull()
        }
        if (legacyMethod == null) {
            ModuleLog.warn("quick_screenshot: no screenshot chord delay method found")
            return 0
        }
        return if (HookRuntime.hookReturning(
                legacyMethod,
                "quick_screenshot/PhoneWindowManager#getScreenshotChordLongPressDelay",
                0L,
            )
        ) 1 else 0
    }

    private fun installAppLinkVerify(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.pm.verify.domain.DomainVerificationUtils")
        if (clazz == null) {
            ModuleLog.warn("app_link_verify: DomainVerificationUtils not found")
            return 0
        }
        var count = 0
        for (method in Reflect.findMethods(clazz, "isDomainVerificationIntent")) {
            if (method.returnType == Boolean::class.javaPrimitiveType &&
                HookRuntime.hookReturning(method, "app_link_verify/DomainVerificationUtils#isDomainVerificationIntent", false)
            ) count++
        }
        return count
    }

    private fun installUntrustedTouch(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.WindowState")
        val method = clazz?.let { Reflect.findMethods(it, "getTouchOcclusionMode", paramCount = 0).firstOrNull() }
        if (method == null) {
            ModuleLog.warn("untrusted_touch: WindowState#getTouchOcclusionMode not found")
            return 0
        }
        return if (HookRuntime.hookReturning(
                method,
                "untrusted_touch/WindowState#getTouchOcclusionMode",
                2,
            )
        ) 1 else 0
    }

    private fun installNotificationChannel(loader: ClassLoader): Int {
        var count = 0
        val clazz = Reflect.loadClass(loader, "android.app.NotificationChannel")
        val blockable = clazz?.let { Reflect.findMethod(it, "isBlockable") }
        if (blockable != null &&
            HookRuntime.hookReturning(blockable, "notification_channel_blockable#isBlockable", true)
        ) count++

        val setBlockable = clazz?.let {
            Reflect.findMethod(it, "setBlockable", Boolean::class.javaPrimitiveType!!)
        }
        if (setBlockable != null &&
            HookRuntime.hook(setBlockable, "notification_channel_blockable#setBlockable") { chain ->
                chain.proceed(arrayOf(true))
            }
        ) count++
        return count
    }

    private fun installLinkTurboToast(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.xiaomi.NetworkBoost.slaservice.SLAToast")
        val method = clazz?.let {
            Reflect.findMethod(
                it,
                "setLinkTurboStatus",
                Boolean::class.javaPrimitiveType!!,
            )
        }
        if (method == null) {
            ModuleLog.warn("link_turbo_toast: SLAToast#setLinkTurboStatus not found")
            return 0
        }
        return if (HookRuntime.hook(method, "link_turbo_toast/SLAToast#setLinkTurboStatus") { chain ->
                chain.proceed(arrayOf(false))
            }
        ) 1 else 0
    }

    private fun installNoOp(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
        vararg expected: Class<*>,
    ): Int {
        val clazz = Reflect.loadClass(loader, className)
        if (clazz == null) {
            ModuleLog.warn("$label: $className not found")
            return 0
        }
        val methods = Reflect.findMethods(clazz, methodName)
            .filter { expected.isEmpty() || it.parameterTypes.contentEquals(expected) }
        if (methods.isEmpty()) {
            ModuleLog.warn("$label: $className#$methodName not found")
            return 0
        }
        return methods.count { method ->
            HookRuntime.hook(method, "$label/$className#$methodName") { null }
        }
    }
}
