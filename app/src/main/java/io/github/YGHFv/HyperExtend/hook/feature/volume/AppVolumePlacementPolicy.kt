/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import io.github.YGHFv.HyperExtend.core.AppVolumeButtonPosition

internal object AppVolumePlacementPolicy {
    fun insertionIndex(position: AppVolumeButtonPosition, silentIndex: Int, dndIndex: Int): Int = when (position) {
        AppVolumeButtonPosition.ABOVE_VOLUME -> 0
        AppVolumeButtonPosition.ABOVE_SILENT, AppVolumeButtonPosition.REPLACE_SILENT -> silentIndex
        AppVolumeButtonPosition.ABOVE_DND, AppVolumeButtonPosition.REPLACE_DND -> dndIndex
        AppVolumeButtonPosition.BELOW_DND -> if (dndIndex < 0) -1 else dndIndex + 1
    }

    /** Footer additions keep the media slider anchored; landscape centers the complete stack. */
    fun footerFits(baseTop: Int, nativeHeight: Int, addedHeight: Int, bottom: Int, centered: Boolean): Boolean {
        if (baseTop < 0 || nativeHeight <= 0 || bottom <= 0 || addedHeight < 0) return false
        val shift = if (centered) (addedHeight + 1) / 2 else 0
        return baseTop >= shift && baseTop.toLong() - shift + nativeHeight + addedHeight <= bottom
    }
}

/** Restore only our own visibility override, never overwrite a newer native state. */
internal class AppVolumeReplacementState {
    private var saved: Int? = null
    fun nativeVisibility(current: Int, hidden: Int): Int = if (current == hidden) saved ?: current else current
    fun hide(current: Int, hidden: Int): Int {
        if (saved == null || current != hidden) saved = current
        return hidden
    }
    fun restore(current: Int, hidden: Int): Int {
        val original = saved
        saved = null
        return if (original != null && current == hidden) original else current
    }
}

/** Track only our added height; host resource/configuration changes establish a new baseline. */
internal class AppVolumeFooterHeight {
    var extra = 0
        private set
    private var owned: Int? = null
    private var baseline: Int? = null
    var measuredExtra = 0
        private set

    fun onLayout(height: Int) { measuredExtra = if (height == owned) extra else 0 }

    fun apply(current: Int, added: Int): Int {
        if (current <= 0) { extra = 0; owned = null; baseline = null; return current }
        if (current != owned) baseline = current
        extra = added.coerceAtLeast(0)
        val target = (baseline ?: current) + extra
        owned = target
        return target
    }

    fun restore(current: Int): Int {
        val original = if (current == owned) baseline ?: current else current
        extra = 0; owned = null; baseline = null
        return original
    }
}
