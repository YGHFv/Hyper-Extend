/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.content.Context
import io.github.YGHFv.HyperExtend.core.MISOUND_LOCK_SELECTION_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.isMiSoundSelectionLocked
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Method

/**
 * MiSound 设置页的耳机锁定。
 *
 * 真正切换发生在不同版本的两套类里：FW 模式使用 `h.r` + `d0.a`，非 FW 模式使用
 * `h.i` / `d0.d`。只读取本机确认存在的方法，不按版本号猜类名。
 */
internal object MiSoundEffectHooks {
    private const val FEATURE = "system_framework_misound_effect"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        val lockConfigured = settings.string(MISOUND_LOCK_SELECTION_KEY).ifBlank { "1" } != "0"
        if (!lockConfigured) {
            ModuleLog.info("misound_effect: selection lock disabled by configuration")
            return 0
        }
        val context = currentApplication()
        var count = 0

        count += hookVoid(loader, "h.i", "Q0", context, Boolean::class.javaPrimitiveType!!)
        count += hookVoid(loader, "d0.d", "J", context, Boolean::class.javaPrimitiveType!!)
        count += hookVoid(loader, "d0.d", "G", context, Boolean::class.javaPrimitiveType!!)

        count += hookVoid(loader, "h.r", "P0", context, String::class.java)
        count += hookVoid(loader, "h.r", "Q0", context, Boolean::class.javaPrimitiveType!!)
        count += hookVoid(loader, "h.r", "R0", context, Boolean::class.javaPrimitiveType!!)
        count += hookVoid(
            loader,
            "d0.a",
            "g",
            context,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        ModuleLog.info("misound_effect: MiSound selection lock hooks=$count")
        return count
    }

    private fun hookVoid(
        loader: ClassLoader,
        className: String,
        methodName: String,
        context: Context?,
        vararg parameterTypes: Class<*>,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val method: Method = Reflect.findMethod(clazz, methodName, *parameterTypes) ?: return 0
        if (method.returnType != Void.TYPE) return 0
        return if (HookRuntime.hook(method, "misound_effect/$className#$methodName") { chain ->
                if (shouldBlock(context ?: currentApplication())) null else chain.proceed()
            }
        ) 1 else 0
    }

    private fun shouldBlock(context: Context?): Boolean =
        context != null && isMiSoundSelectionLocked(context)

    private fun currentApplication(): Context? = Reflect.attempt {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    }
}
