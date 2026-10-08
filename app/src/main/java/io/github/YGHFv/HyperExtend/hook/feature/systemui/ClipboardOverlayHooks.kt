/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Modifier

internal object ClipboardOverlayHooks {
    // Runs only inside privileged SystemUI; MT confirms that the host itself calls this API.
    @SuppressLint("BlockedPrivateApi")
    fun install(loader: ClassLoader): Int {
        val method = NotificationHooks.resolve(loader, SystemUiTargets.clipboardChanged) ?: return 0
        val allowlist = Reflect.findField(method.declaringClass, "sCtsTestPkgList")
            ?.takeIf { Modifier.isStatic(it.modifiers) && it.type == List::class.java } ?: return 0
        val manager = Reflect.findField(method.declaringClass, "mClipboardManagerForUser") ?: return 0
        val source = ClipboardManager::class.java.getDeclaredMethod("getPrimaryClipSource")
        return if (HookRuntime.hook(method, "clipboard_native_overlay/onPrimaryClipChanged") { chain ->
                synchronized(method.declaringClass) {
                    val old = Reflect.attempt { allowlist.get(null) as? List<*> }
                    val pkg = Reflect.attempt { source.invoke(manager.get(chain.thisObject)) as? String }
                    val expanded = if (old != null && !pkg.isNullOrBlank() && pkg !in old) old + pkg else null
                    val changed = expanded != null && Reflect.attempt { allowlist.set(null, expanded); true } == true
                    try {
                        // Keep host suppression, setup, locked-device and sensitive-content checks.
                        chain.proceed()
                    } finally {
                        if (changed) {
                            runCatching { allowlist.set(null, old) }.onFailure {
                                ModuleLog.error("clipboard allowlist restore failed", it)
                            }
                        }
                    }
                }
            }) 1 else 0
    }
}
