/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

internal data class AppVolumeSpring(val damping: Double, val response: Double) {
    fun valid() = damping.isFinite() && damping in .1..1.0 && response.isFinite() && response in .05..2.0
    val durationMillis get() = (-ln(.001) / (damping * 2 * PI / response) * 1000).toLong()

    // Zero-velocity unit step, using the host Folme spring's damping/response.
    fun progress(seconds: Double): Float {
        if (seconds <= 0) return 0f
        val omega = 2 * PI / response
        val decay = exp(-damping * omega * seconds)
        if (damping == 1.0) return (1 - decay * (1 + omega * seconds)).toFloat()
        val frequency = omega * sqrt(1 - damping * damping)
        return (1 - decay * (cos(frequency * seconds) + damping * omega / frequency * sin(frequency * seconds))).toFloat()
    }
}

internal data class AppVolumePanelStyle(
    val screenWidth: Int, val screenHeight: Int, val densityDpi: Int, val rotation: Int, val displayId: Int,
    val top: Int, val right: Int, val padding: Int, val radius: Int,
    val sliderWidth: Int, val sliderHeight: Int, val gap: Int,
    val sourceLeft: Int, val sourceTop: Int, val sourceWidth: Int, val sourceHeight: Int,
    val springs: List<AppVolumeSpring>,
) {
    fun valid(): Boolean = screenWidth in 1..16384 && screenHeight in 1..16384 && densityDpi in 72..1280 &&
        rotation in 0..3 && displayId >= 0 && top in 0 until screenHeight && right in 0 until screenWidth &&
        padding in 0..screenWidth / 4 && radius in 1..screenWidth &&
        sliderWidth in 1..screenWidth && sliderHeight in 1..screenHeight && gap in 0..screenWidth / 4 &&
        sourceLeft in 0 until screenWidth && sourceTop in 0 until screenHeight &&
        sourceWidth in 1..screenWidth - sourceLeft && sourceHeight in 1..screenHeight - sourceTop &&
        springs.size == 6 && springs.all { it.valid() }

    fun matches(width: Int, height: Int, dpi: Int, rotation: Int, display: Int) =
        screenWidth == width && screenHeight == height && densityDpi == dpi && this.rotation == rotation && displayId == display

    // The two hosts can have different status/navigation-bar window origins.
    fun localTop(originY: Int, availableHeight: Int, cardHeight: Int): Int =
        (top - originY).coerceIn(0, (availableHeight - cardHeight).coerceAtLeast(0))

    fun localRight(originX: Int, availableWidth: Int, cardWidth: Int): Int =
        (originX + availableWidth - screenWidth + right).coerceIn(0, (availableWidth - cardWidth).coerceAtLeast(0))
}
