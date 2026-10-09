/*
 * Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0
 * Reference card proportions, with bounds checks for small windows and tablet pages.
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import kotlin.math.min

internal data class AppVolumeCardGeometry(
    val sliderWidth: Int, val sliderHeight: Int, val halfGap: Int,
    val padding: Int, val endMargin: Int, val columns: Int,
) {
    val horizontalPadding get() = (padding - halfGap).coerceAtLeast(0)
    val pagerWidth get() = columns * (sliderWidth + 2 * halfGap)
    val cardWidth get() = pagerWidth + 2 * horizontalPadding

    companion object {
        fun calculate(width: Int, height: Int, density: Float, columns: Int, style: AppVolumePanelStyle? = null): AppVolumeCardGeometry {
            val count = columns.coerceIn(1, 5)
            val short = min(width, height).coerceAtLeast(1)
            val long = maxOf(width, height).coerceAtLeast(1)
            val padding = style?.padding ?: (16 * density).toInt().coerceAtLeast(1)
            val slider = style?.sliderWidth ?: (short * .158f).toInt().coerceAtLeast(1)
            val gap = (style?.gap ?: (short * .038f).toInt()) / 2
            val naturalWidth = count * (slider + 2 * gap) + 2 * (padding - gap).coerceAtLeast(0)
            val naturalHeight = style?.sliderHeight ?: (long * .221f).toInt().coerceAtLeast(1)
            val scale = min(1f, min(
                (width - 2 * padding).coerceAtLeast(1).toFloat() / naturalWidth,
                (height - 4 * padding).coerceAtLeast(1).toFloat() / naturalHeight,
            ))
            return AppVolumeCardGeometry((slider * scale).toInt().coerceAtLeast(1),
                (naturalHeight * scale).toInt().coerceAtLeast(1), (gap * scale).toInt(),
                (padding * scale).toInt().coerceAtLeast(1), padding, count)
        }
    }
}

internal class AppVolumeCardTransition {
    private var generation = 0
    private var closing = false
    fun reset() { generation++; closing = false }
    fun close(): Int { closing = true; return ++generation }
    fun accepts(token: Int): Boolean = closing && token == generation
}
