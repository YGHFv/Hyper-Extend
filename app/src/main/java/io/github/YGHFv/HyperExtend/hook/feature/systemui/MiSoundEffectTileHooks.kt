/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import io.github.YGHFv.HyperExtend.core.MISOUND_LOCK_SELECTION_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.isMiSoundSelectionLocked
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Method

/**
 * 控制中心杜比/小米音效磁贴锁定。
 *
 * 该磁贴不在 SystemUI 本体，而在 `miui.systemui.plugin`；目标来自 18.3.2.57.0：
 * `DolbyAtomsSoundEffectTile#handleClick` 与 `AudioEffectCenter#setEffectActive`。
 */
internal object MiSoundEffectTileHooks {
    private const val FEATURE = "system_framework_misound_effect"
    private const val TILE = "miui.systemui.quicksettings.soundeffect.DolbyAtomsSoundEffectTile"
    private const val CENTER = "miui.systemui.quicksettings.soundeffect.dirac.AudioEffectCenter"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(FEATURE)) return 0
        if (settings.string(MISOUND_LOCK_SELECTION_KEY).ifBlank { "1" } == "0") return 0
        var count = 0
        count += hookVoid(loader, TILE, "handleClick")
        count += hookVoid(loader, TILE, "setDolbyEffectEnable", Boolean::class.javaPrimitiveType!!)
        count += hookVoid(
            loader,
            CENTER,
            "setEffectActive",
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        ModuleLog.info("misound_effect: SystemUI tile lock hooks=$count")
        return count
    }

    private fun hookVoid(
        loader: ClassLoader,
        className: String,
        methodName: String,
        vararg parameterTypes: Class<*>,
    ): Int {
        val clazz = Reflect.loadClass(loader, className) ?: return 0
        val method: Method = Reflect.findMethod(clazz, methodName, *parameterTypes) ?: return 0
        if (method.returnType != Void.TYPE) return 0
        return if (HookRuntime.hook(method, "misound_effect/$className#$methodName") { chain ->
                if (shouldBlock(currentApplication())) null else chain.proceed()
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
