/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object DimTileIconPolicy {
    const val FEATURE = "control_center_dim_tile_icon"
    const val ASSET = "ic_qs_extra_dim_hyperceiler"

    fun eligible(enabled: Boolean, version: Long?, spec: String?, booleanState: Boolean,
                 state: Int, hasIcon: Boolean, hasSupplier: Boolean): Boolean =
        enabled && version == 202602260L && spec == "reduce_brightness" && booleanState &&
            state in 0..2 && hasIcon && !hasSupplier
}

/** Weak keys/values: a drawable callback must not indirectly retain its tile through a view. */
internal class DimTileIconCache<K : Any, V : Any> {
    private data class Entry<K, V : Any>(val key: K, val value: java.lang.ref.WeakReference<V>)
    private val entries = java.util.WeakHashMap<Any, Entry<K, V>>()

    @Synchronized
    fun get(owner: Any, key: K, create: () -> V?): V? {
        entries[owner]?.takeIf { it.key == key }?.value?.get()?.let { return it }
        return create()?.also { entries[owner] = Entry(key, java.lang.ref.WeakReference(it)) }
    }
}
