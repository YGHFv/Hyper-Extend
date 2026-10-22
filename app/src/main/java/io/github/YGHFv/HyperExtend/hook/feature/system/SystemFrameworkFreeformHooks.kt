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

/** 小窗数量、黑名单、气泡与前台固定。 */
internal object SystemFrameworkFreeformHooks {
    private val blackListGetters = setOf(
        "getFreeformBlackList",
        "getFreeformBlackListFromCloud",
        "getAbnormalFreeformBlackList",
        "getAbnormalFreeformBlackListFromCloud",
        "getStartFromFreeformBlackList",
        "getStartFromFreeformBlackListFromCloud",
        "getForegroundPinAppBlackList",
        "getForegroundPinAppBlackListFromCloud",
    )

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        var count = 0
        if (settings.isOn("system_framework_freeform_count")) count += installCount(loader)
        if (settings.isOn("system_framework_freeform_bubble")) count += installBubble(loader)
        if (settings.isOn("system_framework_freeform_foreground_pin")) count += installForegroundPin(loader)
        if (settings.isOn("system_framework_freeform_blacklist")) count += installBlackList(loader)
        return count
    }

    private fun installCount(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.MiuiFreeFormStackDisplayStrategy")
        if (clazz == null) {
            ModuleLog.warn("freeform_count: MiuiFreeFormStackDisplayStrategy not found")
            return 0
        }
        val methods = Reflect.findMethods(clazz, "getMaxMiuiFreeFormStackCount")
            .filter { it.returnType == Int::class.javaPrimitiveType }
        if (methods.isEmpty()) {
            ModuleLog.warn("freeform_count: getMaxMiuiFreeFormStackCount not found")
            return 0
        }
        return methods.count { method ->
            HookRuntime.hookReturning(method, "freeform_count#getMaxMiuiFreeFormStackCount", 256)
        }
    }

    private fun installBubble(loader: ClassLoader): Int {
        var count = 0
        val impl = Reflect.loadClass(loader, "com.android.server.wm.MiuiFreeformServiceImpl")
        val implMethod = impl?.let { Reflect.findMethod(it, "multiFreeFormSupported", Context::class.java) }
        if (implMethod != null &&
            HookRuntime.hookReturning(implMethod, "freeform_bubble/MiuiFreeformServiceImpl#multiFreeFormSupported", true)
        ) count++

        val utils = Reflect.loadClass(loader, "android.util.MiuiMultiWindowUtils")
        val utilMethod = utils?.let { Reflect.findMethod(it, "multiFreeFormSupported", Context::class.java) }
        if (utilMethod != null &&
            HookRuntime.hookReturning(utilMethod, "freeform_bubble/MiuiMultiWindowUtils#multiFreeFormSupported", true)
        ) count++
        if (count == 0) ModuleLog.warn("freeform_bubble: multiFreeFormSupported not found")
        return count
    }

    private fun installForegroundPin(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.MiuiFreeFormGestureController")
        val method = clazz?.let {
            Reflect.findMethods(it, "needForegroundPin", paramCount = 1)
                .firstOrNull { candidate -> candidate.returnType == Boolean::class.javaPrimitiveType }
        }
        if (method == null) {
            ModuleLog.warn("freeform_foreground_pin: needForegroundPin not found")
            return 0
        }
        return if (HookRuntime.hookReturning(
                method,
                "freeform_foreground_pin/MiuiFreeFormGestureController#needForegroundPin",
                true,
            )
        ) 1 else 0
    }

    private fun installBlackList(loader: ClassLoader): Int {
        var count = 0
        val adapter = Reflect.loadClass(loader, "android.util.MiuiMultiWindowAdapter")
        if (adapter != null) {
            for (name in blackListGetters) {
                for (method in Reflect.findMethods(adapter, name)) {
                    if (HookRuntime.hookReturning(
                            method,
                            "freeform_blacklist/MiuiMultiWindowAdapter#$name",
                            ArrayList<String>(),
                        )
                    ) count++
                }
            }
            val empty = ArrayList<String>()
            for (fieldName in listOf(
                "FREEFORM_BLACK_LIST",
                "ABNORMAL_FREEFORM_BLACK_LIST",
                "START_FROM_FREEFORM_BLACK_LIST_ACTIVITY",
                "FOREGROUND_PIN_APP_BLACK_LIST",
            )) {
                val field = Reflect.findField(adapter, fieldName) ?: continue
                if (Reflect.writeField(field, null, empty)) count++
            }
        } else {
            ModuleLog.warn("freeform_blacklist: MiuiMultiWindowAdapter not found")
        }

        val utils = Reflect.loadClass(loader, "android.util.MiuiMultiWindowUtils")
        if (utils != null) {
            for (name in listOf("isForceResizeable", "supportFreeform")) {
                for (method in Reflect.findMethods(utils, name)) {
                    if (method.returnType != Boolean::class.javaPrimitiveType) continue
                    if (HookRuntime.hookReturning(method, "freeform_blacklist/MiuiMultiWindowUtils#$name", true)) {
                        count++
                    }
                }
            }
        }

        val activityTaskManager = Reflect.loadClass(loader, "android.app.ActivityTaskManager")
        if (activityTaskManager != null) {
            for (method in Reflect.findMethods(activityTaskManager, "supportsSplitScreen")) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(method, "freeform_blacklist/ActivityTaskManager#supportsSplitScreen", true)
                ) count++
            }
        }

        val task = Reflect.loadClass(loader, "com.android.server.wm.Task")
        if (task != null) {
            for (method in Reflect.findMethods(task, "isResizeable")) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(method, "freeform_blacklist/Task#isResizeable", true)
                ) count++
            }
        }

        val atm = Reflect.loadClass(loader, "com.android.server.wm.ActivityTaskManagerService")
        if (atm != null) {
            for (method in Reflect.findMethods(atm, "supportsSplitScreen")) {
                if (method.returnType == Boolean::class.javaPrimitiveType &&
                    HookRuntime.hookReturning(method, "freeform_blacklist/ATMS#supportsSplitScreen", true)
                ) count++
            }
            for (method in Reflect.findMethods(atm, "retrieveSettings")) {
                if (HookRuntime.hook(method, "freeform_blacklist/ATMS#retrieveSettings") { chain ->
                        val result = chain.proceed()
                        Reflect.writeField(chain.thisObject, "mDevEnableNonResizableMultiWindow", true)
                        result
                    }
                ) count++
            }
        }
        return count
    }
}
