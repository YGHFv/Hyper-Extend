/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal enum class NotificationBlendScene { SHADE, HEADS_UP, KEYGUARD, EXCLUDED }

internal object NotificationBlendPolicy {
    fun eligible(scene: NotificationBlendScene, enabled: Boolean, keyguard: Boolean,
                 version: Long, display: Int?, main: Boolean) =
        enabled && version == 202602260L && display == 0 && main &&
            scene != NotificationBlendScene.EXCLUDED && (scene != NotificationBlendScene.KEYGUARD || keyguard)

    fun colors(native: IntArray, scene: NotificationBlendScene, night: Boolean): IntArray? {
        if (native.size !in setOf(2, 4, 6) || scene == NotificationBlendScene.EXCLUDED) return null
        val first = when (scene) {
            NotificationBlendScene.SHADE -> if (night) 0xff737373 else 0xff818181
            else -> if (night) 0xff777777 else 0xff919191
        }.toInt()
        val second = when (scene) {
            NotificationBlendScene.SHADE -> if (night) 0xff128f00 else 0xffb3ff00
            NotificationBlendScene.HEADS_UP -> if (night) 0xff1a7300 else 0xffccfa00
            else -> if (night) 0xff155900 else 0xffbdfa00
        }.toInt()
        return native.copyOf().also {
            it[0] = first
            if (it.size >= 4) it[2] = second
            if (it.size == 6) it[4] = if (night) 0 else 0x00ffffff
        }
    }
}

/** The outer glass/AOD effect owns the scene even when it calls a shade fallback. */
internal class NotificationBlendScope<T> {
    private val local = ThreadLocal<T>()
    val current get() = local.get()
    fun <R> within(value: T, block: () -> R): R {
        val previous = local.get()
        if (previous == null) local.set(value)
        return try { block() } finally {
            if (previous == null) local.remove() else local.set(previous)
        }
    }
}
