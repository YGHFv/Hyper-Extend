/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.hardware.display.DisplayManager
import android.os.Build
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** FLAG_SECURE 截屏与录屏保护绕过。 */
internal object SystemFrameworkSecureHooks {
    private const val SECURE_CONTENT_ALLOWED = 1

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn("system_framework_flag_secure")) return 0
        var count = 0
        deoptimizeSystemServer(loader)
        count += hookFalse(
            loader,
            "com.android.server.wm.WindowManagerServiceImpl",
            "notAllowCaptureDisplay",
            "flag_secure/WindowManagerServiceImpl#notAllowCaptureDisplay",
        )
        count += hookFalse(
            loader,
            "com.android.server.wm.WindowManagerService",
            "registerScreenRecordingCallback",
            "flag_secure/WindowManagerService#registerScreenRecordingCallback",
        )
        count += hookNull(
            loader,
            "com.android.server.wm.ActivityTaskManagerService",
            "registerScreenCaptureObserver",
            "flag_secure/ActivityTaskManagerService#registerScreenCaptureObserver",
        )
        count += hookSecureLocked(loader)
        count += hookScreenCapture(loader)
        count += hookDisplayControl(loader)
        count += hookVirtualDisplay(loader)
        return count
    }

    /**
     * `isSecureLocked` 会内联进窗口创建与 relayout 路径。只挂钩子而不反优化时，
     * 已编译的调用方仍会直接使用旧结果，截屏保护看起来“随机失效”。
     */
    private fun deoptimizeSystemServer(loader: ClassLoader) {
        val animator = Reflect.loadClass(loader, "com.android.server.wm.WindowStateAnimator")
        animator?.let { clazz ->
            Reflect.findMethods(clazz, "createSurfaceLocked").forEach {
                HookRuntime.deoptimize(it, "flag_secure/WindowStateAnimator#createSurfaceLocked")
            }
        }
        val wms = Reflect.loadClass(loader, "com.android.server.wm.WindowManagerService")
        wms?.let { clazz ->
            Reflect.findMethods(clazz, "relayoutWindow").forEach {
                HookRuntime.deoptimize(it, "flag_secure/WindowManagerService#relayoutWindow")
            }
        }
        for (index in 0..20) {
            Reflect.loadClass(
                loader,
                "com.android.server.wm.RootWindowContainer\$\$ExternalSyntheticLambda$index",
            )?.let { clazz ->
                Reflect.findMethods(clazz, "accept").forEach {
                    HookRuntime.deoptimize(it, "flag_secure/RootWindowContainer lambda$index#accept")
                }
            }
            Reflect.loadClass(loader, "com.android.server.wm.DisplayContent\$$index")?.let { clazz ->
                Reflect.findMethods(clazz, "test").forEach {
                    HookRuntime.deoptimize(it, "flag_secure/DisplayContent\$$index#test")
                }
            }
        }
    }

    private fun hookFalse(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        return Reflect.findMethods(clazz, methodName)
            .filter { it.returnType == Boolean::class.javaPrimitiveType }
            .count { HookRuntime.hookReturning(it, label, false) }
    }

    private fun hookNull(
        loader: ClassLoader,
        className: String,
        methodName: String,
        label: String,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val methods = Reflect.findMethods(clazz, methodName)
        if (methods.isEmpty()) ModuleLog.warn("$label: method not found")
        return methods.count { HookRuntime.hook(it, label) { null } }
    }

    private fun hookSecureLocked(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.WindowState") ?: return 0
        val method = Reflect.findMethods(clazz, "isSecureLocked", paramCount = 0)
            .firstOrNull { it.returnType == Boolean::class.javaPrimitiveType }
            ?: return 0
        return if (HookRuntime.hook(method, "flag_secure/WindowState#isSecureLocked") { chain ->
                val creatingSurface = Thread.currentThread().stackTrace
                    .take(16)
                    .any {
                        it.methodName == "createSurfaceLocked" ||
                            it.methodName == "setInitialSurfaceControlProperties"
                    }
                if (creatingSurface) chain.proceed() else false
            }
        ) 1 else 0
    }

    private fun hookScreenCapture(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "android.window.ScreenCaptureInternal") ?: return 0
        val args = Reflect.loadClass(loader, "android.window.ScreenCaptureInternal\$CaptureArgs") ?: return 0
        val field = Reflect.findField(args, "mSecureContentPolicy") ?: return 0
        var count = 0
        for (name in listOf("nativeCaptureDisplay", "nativeCaptureLayers")) {
            for (method in Reflect.findMethods(clazz, name)) {
                if (HookRuntime.hook(method, "flag_secure/ScreenCaptureInternal#$name") { chain ->
                        chain.args.getOrNull(0)?.let { captureArgs ->
                            Reflect.writeField(field, captureArgs, SECURE_CONTENT_ALLOWED)
                        }
                        chain.proceed()
                    }
                ) count++
            }
        }
        return count
    }

    private fun hookDisplayControl(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.display.DisplayControl") ?: return 0
        var count = 0
        for (method in Reflect.findMethods(clazz, "createVirtualDisplay")) {
            val secureIndex = when {
                method.parameterCount == 2 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType -> 1
                method.parameterCount == 6 && method.parameterTypes[1] == Boolean::class.javaPrimitiveType -> 1
                else -> continue
            }
            if (HookRuntime.hook(method, "flag_secure/DisplayControl#createVirtualDisplay") { chain ->
                    val args = chain.args.toTypedArray()
                    args[secureIndex] = true
                    chain.proceed(args)
                }
            ) count++
        }
        return count
    }

    private fun hookVirtualDisplay(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.display.VirtualDisplayAdapter") ?: return 0
        val methods = Reflect.findMethods(clazz, "createVirtualDisplayLocked")
        var count = 0
        for (method in methods) {
            if (HookRuntime.hook(method, "flag_secure/VirtualDisplayAdapter#createVirtualDisplayLocked") { chain ->
                    val args = chain.args.toTypedArray()
                    val flagsIndex = args.indices.firstOrNull { index ->
                        index >= 3 && args[index] is Int
                    }
                    if (flagsIndex != null) {
                        args[flagsIndex] = (args[flagsIndex] as Int) or
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE
                    }
                    chain.proceed(args)
                }
            ) count++
        }
        return count
    }

    @Suppress("unused")
    private val supportsSecureContentPolicy: Boolean
        get() = Build.VERSION.SDK_INT >= 36
}
