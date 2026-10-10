/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Adapted from MobileTypeTextCustom; keep empty/no-service names and native measurement.
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.MobileTypeTextSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

internal object MobileTypeTextHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val custom = MobileTypeTextSettings.text(settings.string(MobileTypeTextSettings.TEXT)) ?: return 0
        val name = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl"
        val type = Reflect.loadClass(loader, name) ?: return 0
        val target = type.declaredMethods.singleOrNull {
            it.name == "getMobileTypeName" && it.returnType == String::class.java &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        } ?: return 0
        val caller = Reflect.loadClass(loader, "$name\$special\$\$inlined\$combine\$1\$3") ?: return 0
        val invoke = caller.declaredMethods.singleOrNull {
            it.name == "invokeSuspend" && it.parameterTypes.contentEquals(arrayOf(Any::class.java))
        } ?: return 0
        // The audited showName transform calls getMobileTypeName; do not disable measurement
        // or replace the visibility/provider flows to force text on disconnected/satellite SIMs.
        if (!HookRuntime.deoptimize(invoke, "${MobileTypeTextSettings.FEATURE}/showName")) return 0
        return if (HookRuntime.hookAfter(target, "${MobileTypeTextSettings.FEATURE}/getMobileTypeName") { chain, original ->
                if (original is String) MobileTypeTextSettings.replacement(original, chain.args[0] as Int, custom) else original
            }) 1 else 0
    }
}
