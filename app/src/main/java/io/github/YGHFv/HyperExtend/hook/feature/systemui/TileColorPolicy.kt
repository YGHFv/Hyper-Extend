/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TileColorPolicy {
    private val specialIconSpecs = setOf("quietmode", "papermode", "mute", "cell", "autobrightness", "flashlight", "batterysaver")
    fun genericIcon(spec: String?): Boolean = !spec.isNullOrBlank() && spec !in specialIconSpecs && !spec.startsWith("custom(")

    fun eligible(state: Int, activeBgColor: Int, disabledByPolicy: Boolean, transient: Boolean, restricted: Boolean): Boolean =
        state == 2 && activeBgColor == 0 && !disabledByPolicy && !transient && !restricted

    fun ownsColor(current: Int?, stateful: Boolean, applied: Int): Boolean = !stateful && current == applied
}

internal fun <T> withTileIconColor(requested: Int, read: () -> Int, write: (Int) -> Unit, block: () -> T): T {
    val native = read()
    try {
        write(requested)
        return block()
    } finally {
        if (read() == requested) write(native)
    }
}
