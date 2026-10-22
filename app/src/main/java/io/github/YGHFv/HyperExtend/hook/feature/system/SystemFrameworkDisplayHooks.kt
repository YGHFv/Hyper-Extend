/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.pm.ApplicationInfo
import android.content.Intent
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.UnsafeAccess

/** 深色模式与温控亮度。 */
internal object SystemFrameworkDisplayHooks {

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_background_blur")) count += installBackgroundBlur(loader)
        if (settings.isOn("system_framework_enhance_recents_visibility")) count += installRecentsVisibility(loader)
        if (settings.isOn("system_framework_use_aosp_screenshot")) count += installAospScreenshot(loader)
        if (settings.isOn("system_framework_all_dark_mode")) count += installAllDarkMode(loader)
        if (settings.isOn("system_framework_hide_display_cutout")) count += installDisplayCutout(loader)
        if (settings.isOn("system_framework_thermal_brightness")) count += installThermalBrightness(loader)
        return count
    }

    private fun installBackgroundBlur(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "android.os.SystemProperties") ?: return 0
        val set = Reflect.findMethods(clazz, "set", paramCount = 2).firstOrNull {
            it.parameterTypes.contentEquals(arrayOf(String::class.java, String::class.java))
        }
        if (set != null) {
            for ((key, value) in BLUR_PROPERTIES) {
                runCatching { set.invoke(null, key, value) }
                    .onFailure { ModuleLog.warn("background_blur: set $key failed (${it.javaClass.simpleName})") }
            }
        }
        var count = 0
        for (name in listOf("get", "getInt", "getBoolean")) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (method.parameterCount == 0 || method.parameterTypes[0] != String::class.java) continue
                val forced = when (method.returnType) {
                    String::class.java -> true
                    Int::class.javaPrimitiveType -> true
                    Boolean::class.javaPrimitiveType -> true
                    else -> false
                }
                if (!forced) continue
                if (HookRuntime.hook(method, "background_blur/SystemProperties#$name") { chain ->
                        val key = chain.args.getOrNull(0) as? String
                        val value = key?.let(BLUR_PROPERTIES::get) ?: return@hook chain.proceed()
                        when (method.returnType) {
                            String::class.java -> value
                            Int::class.javaPrimitiveType -> value.toInt()
                            Boolean::class.javaPrimitiveType -> value.toBoolean()
                            else -> chain.proceed()
                        }
                    }
                ) count++
            }
        }
        return count
    }

    private fun installRecentsVisibility(loader: ClassLoader): Int {
        val intent = Reflect.loadClass(loader, "android.content.Intent") ?: return 0
        val methods = Reflect.findMethods(intent, "setFlags", paramCount = 1).filter {
            it.returnType == Intent::class.java
        }
        return methods.count { method ->
            HookRuntime.hook(method, "enhance_recents/Intent#setFlags") { chain ->
                val flags = chain.args.getOrNull(0) as? Int ?: return@hook chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = flags and 0xFF7FFFFF.toInt()
                chain.proceed(args)
            }
        }
    }

    private fun installAospScreenshot(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.internal.util.ScreenshotHelperStub") ?: return 0
        val methods = Reflect.findMethods(clazz, "getServiceComponent", paramCount = 1).filter {
            it.returnType == String::class.java
        }
        if (methods.isEmpty()) {
            ModuleLog.warn("aosp_screenshot: ScreenshotHelperStub#getServiceComponent not found")
            return 0
        }
        return methods.count {
            HookRuntime.hookReturning(
                it,
                "aosp_screenshot/ScreenshotHelperStub#getServiceComponent",
                "com.android.systemui/com.android.systemui.screenshot.TakeScreenshotService",
            )
        }
    }

    private fun installAllDarkMode(loader: ClassLoader): Int {
        val manager = Reflect.loadClass(loader, "com.android.server.ForceDarkAppListManager")
        val build = Reflect.loadClass(loader, "miui.os.Build")
        val field = build?.let { Reflect.staticFieldOrNull(it, "IS_INTERNATIONAL_BUILD") }
        if (manager == null || field == null) {
            ModuleLog.warn("all_dark_mode: ForceDarkAppListManager or miui.os.Build not found")
            return 0
        }
        if (!UnsafeAccess.isAvailable) {
            ModuleLog.warn("all_dark_mode: Unsafe unavailable; temporary region switch disabled")
            return 0
        }
        var count = 0
        for (method in Reflect.findMethods(manager, "getDarkModeAppList")) {
            if (HookRuntime.hook(method, "all_dark_mode/ForceDarkAppListManager#getDarkModeAppList") { chain ->
                    val original = Reflect.attempt { field.getBoolean(null) } ?: false
                    UnsafeAccess.putStaticBoolean(field, true)
                    try {
                        chain.proceed()
                    } finally {
                        UnsafeAccess.putStaticBoolean(field, original)
                    }
                }
            ) count++
        }
        for (method in Reflect.findMethods(manager, "shouldShowInSettings")) {
            if (method.parameterCount != 1 || method.returnType != Boolean::class.javaPrimitiveType) continue
            if (HookRuntime.hook(method, "all_dark_mode/ForceDarkAppListManager#shouldShowInSettings") { chain ->
                    val info = chain.args.getOrNull(0) as? ApplicationInfo
                    info != null &&
                        info.flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
                        info.uid >= 10_000
                }
            ) count++
        }
        return count
    }

    private fun installDisplayCutout(loader: ClassLoader): Int {
        val cutout = Reflect.loadClass(loader, "android.view.DisplayCutout") ?: return 0
        val noCutout = Reflect.staticFieldOrNull(cutout, "NO_CUTOUT")?.let {
            runCatching { it.get(null) }.getOrNull()
        }
        if (noCutout == null) {
            ModuleLog.warn("hide_cutout: DisplayCutout.NO_CUTOUT unavailable")
            return 0
        }
        var count = 0
        for (method in Reflect.findMethods(cutout, "pathAndDisplayCutoutFromSpec")) {
            if (HookRuntime.hook(method, "hide_cutout/DisplayCutout#pathAndDisplayCutoutFromSpec") { chain ->
                    val args = chain.args.toTypedArray()
                    if (args.isNotEmpty()) args[0] = "M 0,0 H 0 V 0 Z"
                    if (args.size > 1) args[1] = null
                    chain.proceed(args)
                }
            ) count++
        }
        for (name in listOf("fromResourcesRectApproximation", "fromSpec")) {
            for (method in Reflect.findMethods(cutout, name)) {
                if (method.returnType != cutout) continue
                if (HookRuntime.hook(method, "hide_cutout/DisplayCutout#$name") { chain ->
                        chain.proceed() ?: noCutout
                    }
                ) count++
            }
        }
        val logical = Reflect.loadClass(loader, "com.android.server.display.LogicalDisplay")
        val displayInfo = logical?.let { Reflect.findMethods(it, "getDisplayInfoLocked", paramCount = 0) }
            ?.firstOrNull()
        val cutoutField = displayInfo?.returnType?.let { Reflect.findField(it, "displayCutout") }
        if (displayInfo != null && cutoutField != null) {
            if (HookRuntime.hook(displayInfo, "hide_cutout/LogicalDisplay#getDisplayInfoLocked") { chain ->
                    val result = chain.proceed()
                    if (result != null && Reflect.readField(result, "displayCutout") == null) {
                        Reflect.writeField(result, "displayCutout", noCutout)
                    }
                    result
                }
            ) count++
        }
        return count
    }

    private fun installThermalBrightness(loader: ClassLoader): Int {
        var count = 0
        val powerController = Reflect.loadClass(loader, "com.android.server.display.DisplayPowerControllerImpl")
        if (powerController != null) {
            for (constructor in powerController.declaredConstructors) {
                if (HookRuntime.hook(constructor, "thermal_brightness/DisplayPowerControllerImpl#<init>") { chain ->
                        val result = chain.proceed()
                        Reflect.writeField(chain.thisObject, "SUPPORT_TEMEPERATURE_CONTROL", false)
                        Reflect.writeField(chain.thisObject, "mThermalBrightnessControlAvailable", false)
                        Reflect.writeField(chain.thisObject, "mApplyThermalBrightnessRate", false)
                        result
                    }
                ) count++
            }
            for (method in Reflect.findMethods(powerController, "adjustBrightnessByThermal")) {
                if (method.parameterCount < 1 || method.returnType != Float::class.javaPrimitiveType) continue
                if (HookRuntime.hook(method, "thermal_brightness/DisplayPowerControllerImpl#adjustBrightnessByThermal") { chain ->
                        chain.proceed()
                        chain.args.getOrNull(0) ?: 0f
                    }
                ) count++
            }
            for (method in Reflect.findMethods(powerController, "updateThermalBrightness")) {
                if (HookRuntime.hook(method, "thermal_brightness/DisplayPowerControllerImpl#updateThermalBrightness") {
                        null
                    }
                ) count++
            }
        }

        val automatic = Reflect.loadClass(loader, "com.android.server.display.AutomaticBrightnessControllerImpl")
        if (automatic != null) {
            for (constructor in automatic.declaredConstructors) {
                if (HookRuntime.hook(constructor, "thermal_brightness/AutomaticBrightnessControllerImpl#<init>") { chain ->
                        val result = chain.proceed()
                        Reflect.writeField(chain.thisObject, "SUPPORT_TEMEPERATURE_CONTROL", false)
                        result
                    }
                ) count++
            }
        }

        val brightness = Reflect.loadClass(loader, "com.android.server.display.ThermalBrightnessController")
        if (brightness != null) {
            for (method in Reflect.findMethods(brightness, "updateThermalBrightnessIfNeeded")) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(
                        method,
                        "thermal_brightness/ThermalBrightnessController#updateThermalBrightnessIfNeeded",
                        false,
                    )
                ) count++
            }
            for (method in Reflect.findMethods(brightness, "updateConditionState")) {
                if (HookRuntime.hook(method, "thermal_brightness/ThermalBrightnessController#updateConditionState") {
                        null
                    }
                ) count++
            }
        }

        for (className in listOf(
            "com.android.server.display.ThermalObserver",
            "com.android.server.audio.MultimediaMiPerception.utils.ThermalHelper",
        )) {
            val clazz = Reflect.loadClass(loader, className) ?: continue
            for (method in Reflect.findMethods(clazz, "updateTemperature")) {
                if (HookRuntime.hook(method, "thermal_brightness/$className#updateTemperature") { null }) count++
            }
        }
        return count
    }

    private val BLUR_PROPERTIES = mapOf(
        "persist.sys.background_blur_supported" to "true",
        "persist.sys.background_blur_status_default" to "true",
        "persist.sys.background_blur_mode" to "0",
        "persist.sys.background_blur_version" to "2",
    )
}
