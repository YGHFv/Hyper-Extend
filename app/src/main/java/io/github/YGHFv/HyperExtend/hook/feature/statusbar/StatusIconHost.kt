/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import java.lang.reflect.Field
import java.lang.reflect.Modifier

internal class StatusIconHost {
    fun eligible(view: View): Boolean = runCatching {
        StatusIconPolicy.eligible(view.display?.displayId ?: view.context.hostDisplayIdOrNull(),
            Looper.myLooper() == Looper.getMainLooper())
    }.getOrDefault(false)

    companion object {
        fun field(loader: ClassLoader, owner: String, name: String, type: String): Field =
            Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(this.type.name == type && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
    }
}
