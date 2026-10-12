/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object CardTileListPolicy {
    fun eligible(enabled: Boolean, systemUi: Long, plugin: Long, display: Int?, main: Boolean) =
        enabled && systemUi == 202602260L && plugin == 183022200L && display == 0 && main
}

/** Only successful creations in one detail invocation suppress a later duplicate. */
internal class CardDetailCreationScope {
    private val local = ThreadLocal<MutableMap<Any, MutableSet<String>>>()
    fun contains(owner: Any, spec: String) = local.get()?.get(owner)?.contains(spec) == true
    fun created(owner: Any, spec: String) {
        local.get()?.getOrPut(owner) { HashSet() }?.add(spec)
    }
    val active get() = local.get() != null
    fun <T> within(block: () -> T): T {
        val previous = local.get()
        local.set(java.util.IdentityHashMap())
        return try { block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
