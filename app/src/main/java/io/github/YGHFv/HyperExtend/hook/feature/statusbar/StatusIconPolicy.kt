/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object StatusIconPolicy {
    @Deprecated("版本号不参与运行期门禁")
    fun eligible(version: Long?, display: Int?, mainThread: Boolean) = eligible(display, mainThread)
    fun eligible(display: Int?, mainThread: Boolean) =
        display == 0 && mainThread

    fun hideOperatorIcon(enabled: Boolean) = enabled
    @Deprecated("版本号不参与运行期门禁")
    fun hideOperatorIcon(version: Long?, enabled: Boolean) = hideOperatorIcon(enabled)

    // QS_FAKE deliberately mirrors HOME during the native shade transition.
    fun controlCenter(location: String?): Boolean? = when (location) {
        "QS" -> true
        "HOME", "KEYGUARD", "QS_FAKE" -> false
        else -> null
    }

    fun blocked(mode: Int, controlCenter: Boolean): Boolean? = when (mode) {
        1 -> false
        2 -> true
        11 -> !controlCenter
        12 -> controlCenter
        else -> null
    }

    fun blockList(native: List<String>, modes: Map<String, Int>, controlCenter: Boolean): List<String> {
        val result = ArrayList(native)
        modes.forEach { (slot, mode) ->
            when (blocked(mode, controlCenter)) {
                true -> if (slot !in result) result.add(slot)
                false -> result.removeAll { it == slot }
                null -> Unit
            }
        }
        return result
    }

    fun restoreVisibility(current: Int, native: Int, owned: Boolean): Int? =
        native.takeIf { owned && current == 8 }

    fun restoreList(current: List<String>, native: List<String>, applied: List<String>): List<String>? =
        native.takeIf { current == applied }

    fun notificationLimit(native: Int, available: Int, type: String, requested: Int): Int? {
        if (native < 0 || available < 0 || type !in setOf("MaximumAmount", "MaximumIndex")) return null
        // Zero is a native suppression state, not permission to resurrect icons.
        return if (native == 0) 0 else requested.coerceIn(1, 15).coerceAtMost(available)
    }
}
