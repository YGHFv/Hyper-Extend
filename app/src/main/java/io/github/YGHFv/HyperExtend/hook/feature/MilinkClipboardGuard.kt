/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature

import android.content.Context
import android.net.Uri
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.io.IOException

internal object MilinkClipboardGuard {
    fun install(loader: ClassLoader): Int {
        val type = Reflect.loadClass(loader,
            "com.xiaomi.dist.universalclipboardservice.utils.FileUtil") ?: return 0
        val method = type.declaredMethods.singleOrNull {
            it.name == "isAvailableUri" && it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterTypes.contentEquals(arrayOf(Uri::class.java, Context::class.java))
        } ?: return 0
        return if (HookRuntime.hook(method, "milink_clipboard_guard/isAvailableUri") { chain ->
                try {
                    chain.proceed()
                } catch (_: IllegalStateException) {
                    // MediaProvider rejects pending/trashed rows even with a URI read grant.
                    ModuleLog.warn("milink_clipboard_guard: unavailable media item")
                    false
                } catch (_: SecurityException) {
                    false
                } catch (_: IOException) {
                    false
                }
            }) 1 else 0
    }
}
