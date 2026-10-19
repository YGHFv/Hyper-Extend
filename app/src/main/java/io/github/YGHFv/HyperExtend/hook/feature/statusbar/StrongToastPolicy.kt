/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object StrongToastPolicy {
    @Deprecated("版本号不参与运行期门禁")
    fun eligible(enabled: Boolean, version: Long, display: Int?, action: String?) =
        eligible(enabled, display, action)
    fun eligible(enabled: Boolean, display: Int?, action: String?) =
        enabled && display == 0 && action == "strong_toast_action"
}

/** Only the parsed delivery from this synchronous command may be suppressed. */
internal class StrongToastScope {
    private val current = ThreadLocal<Pair<Any, Any>?>()
    fun matches(owner: Any?, bundle: Any?) = current.get()?.let { it.first === owner && it.second === bundle } == true
    fun <T> within(owner: Any, bundle: Any, block: () -> T): T {
        val previous = current.get()
        current.set(owner to bundle)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
