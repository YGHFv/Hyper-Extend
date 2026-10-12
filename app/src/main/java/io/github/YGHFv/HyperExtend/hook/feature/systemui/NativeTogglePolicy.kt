/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NativeTogglePolicy {
    fun append(original: String?, spec: String): String? {
        if (original.isNullOrBlank()) return original
        return (original.split(',').map(String::trim).filter(String::isNotEmpty) + spec).distinct().joinToString(",")
    }
    fun canAct(enabled: Boolean, safe: Boolean, owner: Boolean, unlocked: Boolean,
        listening: Boolean, age: Long) = enabled && !safe && owner && unlocked && listening && age in 0..1500
}

/** One native setter, preceded by fresh comparison; no blind retry or rollback over an external change. */
internal object NativeToggleTransaction {
    interface Backend {
        fun allowed(): Boolean
        fun read(): Boolean?
        fun write(enabled: Boolean)
    }
    fun toggle(expected: Boolean, backend: Backend): Boolean {
        if (!backend.allowed() || backend.read() != expected || !backend.allowed()) return false
        backend.write(!expected)
        if (!backend.allowed()) return false
        val confirmed = backend.read() == !expected
        return confirmed && backend.allowed()
    }
}
