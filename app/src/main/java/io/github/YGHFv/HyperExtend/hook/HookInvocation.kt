/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

/** Never replay a host call after a failing postprocessor, including void/null results. */
internal class HookInvocation {
    private var outcome: Result<Any?>? = null

    fun proceed(call: () -> Any?): Any? {
        val result = outcome ?: runCatching(call).also { outcome = it }
        return result.getOrThrow()
    }

    fun protect(block: () -> Any?, fallback: () -> Any?, report: (Throwable) -> Unit): Any? = try {
        block()
    } catch (failure: Throwable) {
        runCatching { report(failure) }
        proceed(fallback)
    }
}
