/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

/** Compose native content-to-dialog transforms without replaying the dialog's own motion. */
internal class AppVolumeEntryMotion {
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set
    var alpha = 1f
        private set
    private var layoutX = 0f
    private var layoutY = 0f
    private var mappedX = 0f
    private var mappedY = 0f

    fun reset() {
        scaleX = 1f; scaleY = 1f; alpha = 1f
        layoutX = 0f; layoutY = 0f; mappedX = 0f; mappedY = 0f
    }

    // Include the content first, then its ancestors, stopping before the shared dialog.
    fun include(
        left: Float, top: Float, pivotX: Float, pivotY: Float,
        scaleX: Float, scaleY: Float, translationX: Float, translationY: Float, alpha: Float,
    ) {
        layoutX += left
        layoutY += top
        mappedX = left + translationX + pivotX * (1f - scaleX) + mappedX * scaleX
        mappedY = top + translationY + pivotY * (1f - scaleY) + mappedY * scaleY
        this.scaleX *= scaleX
        this.scaleY *= scaleY
        this.alpha *= alpha
    }

    fun translationX(centerX: Float): Float = mappedX - layoutX + (scaleX - 1f) * (centerX - layoutX)
    fun translationY(centerY: Float): Float = mappedY - layoutY + (scaleY - 1f) * (centerY - layoutY)
}
