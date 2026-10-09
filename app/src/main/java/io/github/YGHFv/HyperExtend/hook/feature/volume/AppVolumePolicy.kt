/*
 * Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0
 * Adapted playback and entry rules; added user-id, lock and geometry guards.
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

internal object AppVolumePolicy {
    fun activeMedia(uid: Int, state: Int, usage: Int, stream: Int, packages: List<String>): Boolean =
        uid >= 0 && uid % 100000 >= 10000 && state == 2 && (usage == 1 || stream == 3) &&
            packages.isNotEmpty() && "com.miui.miwallpaper" !in packages

    fun visible(dialog: Boolean, expanded: Boolean, locked: Boolean, playing: Boolean, conflict: Boolean): Boolean =
        dialog && !expanded && !locked && playing && !conflict

    fun offset(baseMargin: Int, rowHeight: Int, visible: Boolean, centered: Boolean = false): Int {
        val shift = if (centered) rowHeight / 2 + rowHeight % 2 else rowHeight
        return if (visible && rowHeight > 0 && baseMargin >= shift) shift else 0
    }

    fun margin(current: Int, oldOffset: Int, newOffset: Int): Int = current + oldOffset - newOffset
}

/** Restore before a native state write; otherwise compensate relative to the last applied margin. */
internal class AppVolumeGeometry {
    var offset = 0
        private set

    fun restore(current: Int): Int = (current + offset).also { offset = 0 }

    fun refresh(current: Int, height: Int, visible: Boolean, centered: Boolean = false): Int {
        val base = current + offset
        offset = AppVolumePolicy.offset(base, height, visible, centered)
        return base - offset
    }
}

internal class AppVolumeLifecycle {
    var visible = false
        private set
    var dismissing = false
        private set
    var opening = false
        private set
    private var generation = 0

    fun show() { cancelRequest(); visible = true; dismissing = false }
    fun dismiss() { cancelRequest(); visible = false; dismissing = true }
    fun detach() { cancelRequest(); visible = false; dismissing = false }
    fun cancelRequest() { if (opening) generation++; opening = false }
    fun beginRequest(): Int? {
        if (!visible || dismissing || opening) return null
        opening = true
        return ++generation
    }
    fun accepts(token: Int): Boolean = visible && !dismissing && opening && generation == token
}
