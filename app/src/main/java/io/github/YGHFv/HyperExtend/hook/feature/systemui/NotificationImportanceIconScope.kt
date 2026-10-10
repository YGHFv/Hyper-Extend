/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal class NotificationImportanceIconScope {
    private val entry = ThreadLocal<Any>()

    fun suppress(target: Any?, effect: Int?, importance: Int?): Boolean =
        target != null && target === entry.get() && effect == 32 && importance in 0..1

    fun <T> withEntry(target: Any?, block: () -> T): T {
        val previous = entry.get()
        if (target == null) entry.remove() else entry.set(target)
        return try { block() } finally {
            if (previous == null) entry.remove() else entry.set(previous)
        }
    }
}
