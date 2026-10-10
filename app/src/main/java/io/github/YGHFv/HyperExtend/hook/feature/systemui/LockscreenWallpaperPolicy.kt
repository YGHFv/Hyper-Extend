/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockscreenWallpaperPolicy {
    fun eligible(version: Long?, mainThread: Boolean, enabled: Boolean, needAnimation: Boolean,
                 systemAnimations: Boolean, attached: Boolean, displayId: Int?, defaultTheme: Boolean,
                 keyguardShowing: Boolean, fullAod: Boolean, depth: Boolean, video: Boolean,
                 flip: Boolean, occluded: Boolean, fromGone: Boolean, bouncer: Boolean,
                 dismissing: Boolean, superSave: Boolean): Boolean =
        version == 202602260L && mainThread && enabled && needAnimation && systemAnimations && attached &&
            displayId == 0 && defaultTheme && keyguardShowing && !fullAod && !depth && !video &&
            !flip && !occluded && !fromGone && !bouncer && !dismissing && !superSave
}

/** Match one native ease by identity, never every AnimConfig on the same thread. */
internal class LockscreenWallpaperScope {
    internal class Frame(val nativeEase: Any, val duration: Long) { var consumed = false }
    private val current = ThreadLocal<Frame?>()

    fun take(ease: Any?): Long? {
        val frame = current.get() ?: return null
        if (frame.consumed || ease !== frame.nativeEase) return null
        frame.consumed = true
        return frame.duration
    }

    fun <T> withFrame(frame: Frame?, block: () -> T): T {
        val previous = current.get()
        current.set(frame)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
