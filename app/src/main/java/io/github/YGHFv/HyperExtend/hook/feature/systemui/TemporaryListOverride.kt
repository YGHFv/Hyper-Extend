/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/** Serialize our overrides, but preserve cloud refreshes that replace either list. */
internal fun <T> withTemporaryLists(
    lock: Any,
    get: (Int) -> Any?,
    set: (Int, Any?) -> Unit,
    block: () -> T,
): T = synchronized(lock) {
    val original = arrayOf(get(0), get(1))
    val temporary = arrayOf(arrayListOf<String>(), arrayListOf<String>())
    try {
        set(0, temporary[0])
        set(1, temporary[1])
        block()
    } finally {
        for (index in 0..1) if (get(index) === temporary[index]) set(index, original[index])
    }
}
