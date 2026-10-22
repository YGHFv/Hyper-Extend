/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.res.Resources
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SYSTEM_FRAMEWORK_MIPAD_STYLUS_VERSION
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 小米平板触控笔、ESC 与多指手势修补。 */
internal object SystemFrameworkMipadHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val wanted = listOf(
            "system_framework_mipad_stylus_gesture",
            "system_framework_mipad_stylus_bluetooth",
            "system_framework_mipad_restore_esc",
            "system_framework_mipad_gesture_fingers",
        ).any(settings::isOn)
        if (!wanted) return 0
        if (!isTablet()) {
            ModuleLog.info("mipad: non-tablet device, hooks skipped")
            return 0
        }
        var count = 0
        if (settings.isOn("system_framework_mipad_stylus_gesture")) {
            count += installStylusGesture(loader)
        }
        if (settings.isOn("system_framework_mipad_stylus_bluetooth")) {
            count += installStylusBluetooth(loader, settings)
        }
        if (settings.isOn("system_framework_mipad_restore_esc")) {
            count += installRestoreEsc(loader)
        }
        if (settings.isOn("system_framework_mipad_gesture_fingers")) {
            count += installGestureFingers(loader)
        }
        return count
    }

    private fun isTablet(): Boolean =
        Resources.getSystem().configuration.smallestScreenWidthDp >= 600

    private fun installStylusGesture(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.input.stylus.MiuiStylusShortcutManager")
            ?: return 0
        return listOf("needInterceptBeforeDispatching", "shouldInterceptKey").sumOf { name ->
            Reflect.findMethods(clazz, name)
                .filter { it.returnType == Boolean::class.javaPrimitiveType }
                .count { HookRuntime.hookReturning(it, "mipad_stylus_gesture#$name", false) }
        }
    }

    private fun installStylusBluetooth(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, "com.miui.server.input.stylus.MiuiStylusDeviceListener")
            ?: return 0
        val version = settings.number(SYSTEM_FRAMEWORK_MIPAD_STYLUS_VERSION, 2, 1, 2)
        refillTouchMode(loader, version)
        var count = 0
        for (constructor in clazz.declaredConstructors) {
            if (HookRuntime.hook(constructor, "mipad_stylus_bluetooth/MiuiStylusDeviceListener#<init>") { chain ->
                    val result = chain.proceed()
                    refillTouchMode(loader, version)
                    result
                }
            ) count++
        }
        for (method in clazz.declaredMethods) {
            if (HookRuntime.hook(method, "mipad_stylus_bluetooth/MiuiStylusDeviceListener#${method.name}") { chain ->
                    val result = chain.proceed()
                    refillTouchMode(loader, version)
                    result
                }
            ) count++
        }
        return count
    }

    private fun refillTouchMode(loader: ClassLoader, version: Int) {
        val clazz = Reflect.loadClass(loader, "miui.util.ITouchFeature") ?: return
        val instance = Reflect.findMethods(clazz, "getInstance", paramCount = 0)
            .firstOrNull()
            ?.let { Reflect.attempt { it.invoke(null) } }
            ?: return
        Reflect.attempt { Reflect.callWith(instance, "setTouchMode", 0, 20, 0x10 or version) }
    }

    private fun installRestoreEsc(loader: ClassLoader): Int {
        val inputStub = Reflect.loadClass(loader, "com.android.server.input.InputManagerServiceStubImpl")
        inputStub?.let { clazz ->
            for (name in listOf("switchPadMode", "init")) {
                Reflect.findMethods(clazz, name).forEach { method ->
                    HookRuntime.deoptimize(method, "mipad_restore_esc/InputManagerServiceStubImpl#$name")
                }
            }
        }
        val clazz = Reflect.loadClass(loader, "com.android.server.input.config.InputCommonConfig")
            ?: return 0
        val method = Reflect.findMethods(clazz, "setPadMode", paramCount = 1)
            .firstOrNull { it.returnType == Void.TYPE }
            ?: return 0
        return if (HookRuntime.hook(method, "mipad_restore_esc/InputCommonConfig#setPadMode") { chain ->
                val args = chain.args.toTypedArray()
                args[0] = false
                chain.proceed(args)
            }
        ) 1 else 0
    }

    private fun installGestureFingers(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.miui.server.input.gesture.multifingergesture.gesture.BaseMiuiMultiFingerGesture",
        ) ?: return 0
        val method = Reflect.findMethods(clazz, "getFunctionNeedFingerNum", paramCount = 0)
            .firstOrNull { it.returnType == Int::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hookReturning(
                method,
                "mipad_gesture_fingers/BaseMiuiMultiFingerGesture#getFunctionNeedFingerNum",
                4,
            )
        ) 1 else 0
    }
}
