/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object SignalOrderPolicy {
    private val before = listOf("mobile", "demo_mobile", "no_sim", "wifi", "demo_wifi")
    private val after = listOf("wifi", "demo_wifi", "no_sim", "mobile", "demo_mobile")

    fun permutation(names: List<String>, published: Boolean): List<Int>? {
        if (published || names.size > 256 || names.distinct().size != names.size) return null
        val positions = names.indices.filter { names[it] in before }
        if (positions.map { names[it] } != before) return null
        val result = names.indices.toMutableList()
        positions.forEachIndexed { i, position -> result[position] = names.indexOf(after[i]) }
        return result
    }
}
