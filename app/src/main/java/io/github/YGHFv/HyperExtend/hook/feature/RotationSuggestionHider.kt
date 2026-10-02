/*
 * Copyright (C) 2026 YGHFv
 *
 * This file is part of HyperExtend.
 */

package io.github.YGHFv.HyperExtend.hook.feature

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.DexScan
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

/** Hides the rotation suggestion floating button in SystemUI. */
internal object RotationSuggestionHider {

    private const val FEATURE = "rotation_suggestion"
    private const val CONTROLLER_CLASS = "com.android.systemui.shared.rotation.RotationButtonController"
    private const val CONTROLLER_SIMPLE_NAME = "RotationButtonController"
    private const val SYSTEMUI_PREFIX = "com.android.systemui"

    fun install(loader: ClassLoader): Int {
        val controller = Reflect.loadClass(loader, CONTROLLER_CLASS)
            ?: DexScan.findBySimpleName(
                loader,
                HookRuntime.codePaths,
                CONTROLLER_SIMPLE_NAME,
                SYSTEMUI_PREFIX,
            )
            ?: run {
                ModuleLog.warn("$FEATURE: RotationButtonController not found")
                return 0
            }

        var installed = 0
        val canShow = Reflect.firstMethod(controller, "canShowRotationButton") {
            it.parameterCount == 0 && it.returnType == Boolean::class.javaPrimitiveType
        }
        if (HookRuntime.hookReturning(canShow, "$FEATURE/${controller.simpleName}#canShowRotationButton", false)) {
            installed++
        }

        val stateMethods = Reflect.findMethods(controller, "setRotateSuggestionButtonState", 2)
            .filter { method ->
                method.parameterTypes.all { it == Boolean::class.javaPrimitiveType } &&
                    method.returnType == Void.TYPE
            }
        for (method in stateMethods) {
            if (HookRuntime.hook(method, "$FEATURE/${controller.simpleName}#setRotateSuggestionButtonState") { chain ->
                    val args = chain.args.toMutableList()
                    args[0] = false
                    chain.proceed(args.toTypedArray())
                }
            ) {
                installed++
            }
        }

        if (installed == 0) {
            ModuleLog.warn("$FEATURE: no hook installed")
        } else {
            ModuleLog.info("$FEATURE: rotation suggestion hidden ($installed hook(s))")
        }
        return installed
    }
}
