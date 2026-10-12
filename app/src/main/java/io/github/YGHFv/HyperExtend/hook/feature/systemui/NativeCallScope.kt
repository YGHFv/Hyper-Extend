/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal class NativeCallScope<T> {
    private val current = ThreadLocal<T>()
    val value: T? get() = current.get()
    fun <R> within(value: T?, action: () -> R): R {
        val previous = current.get()
        if (value == null) current.remove() else current.set(value)
        return try { action() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
