/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaDarkPolicy {
    fun enabled(version: Long, mainThread: Boolean, tinyScreen: Boolean, blocked: Boolean): Boolean =
        version == 202602260L && mainThread && !tinyScreen && !blocked

    fun nightMode(uiMode: Int): Int = (uiMode and 0x30.inv()) or 0x20
}

/** A synchronous native color update must never retain our configuration context. */
internal fun <T : Any, R> withMediaColorContext(read: () -> T, write: (T) -> Unit, temporary: T, block: () -> R): R {
    val original = read()
    return try {
        write(temporary)
        block()
    } finally {
        // Do not overwrite a newer context published by another participant.
        if (read() === temporary) write(original)
    }
}

/** An older effect must not overwrite a newer native background during restoration. */
internal class MediaEffectOwnership<T : Any> {
    private var owned: Any? = null
    private var restore: T? = null

    fun record(background: Any?, restoration: T) { owned = background; restore = restoration }
    fun clear() { owned = null; restore = null }
    fun take(current: Any?): T? {
        val result = restore.takeIf { current === owned }
        owned = null
        restore = null
        return result
    }
}
