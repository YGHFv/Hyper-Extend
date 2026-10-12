/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

internal object MediaProgressGeometry {
    fun alpha(color: Int, factor: Float): Int =
        (color and 0x00ffffff) or (((color ushr 24) * factor.coerceIn(0f, 1f)).toInt() shl 24)
    fun fraction(progress: Int, minimum: Int, maximum: Int): Float? {
        val span = maximum.toLong() - minimum
        if (span <= 0) return null
        return ((progress.toLong() - minimum).coerceIn(0L, span).toDouble() / span).toFloat()
    }
    fun animate(enabled: Boolean, listening: Boolean, playing: Boolean, scrubbing: Boolean): Boolean =
        enabled && listening && playing && !scrubbing
    fun phase(uptime: Long): Float = (uptime.coerceAtLeast(0) % 2500L) / 2500f
}

/** Draw only: the host still owns the SeekBar, its touch listener and accessibility node. */
internal class MediaProgressDrawing {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, density: Float,
             fraction: Float, rtl: Boolean, mode: Int, thumb: Int, round: Boolean, comet: Boolean,
             thickness: Float, corner: Float, foreground: Int, background: Int, phase: Float) {
        val saved = canvas.save()
        try {
            canvas.clipRect(left, top, left + width, top + height)
            canvas.translate(if (rtl) left + width else left, top + height / 2f)
            if (rtl) canvas.scale(-1f, 1f)
            val h = min(thickness, height / 2f)
            val radius = min(corner, h / 2f)
            val end = width * fraction
            paint.style = Paint.Style.FILL
            paint.color = background
            if (mode == 1) {
                paint.strokeWidth = 2f * density
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(0f, 0f, width, 0f, paint)
            } else canvas.drawRoundRect(0f, -h / 2f, width, h / 2f, radius, radius, paint)
            paint.color = foreground
            if (end > 0f) {
                if (mode == 1) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f * density
                    path.rewind()
                    // Bounded sampling even if a malformed layout reports an enormous width.
                    val samples = (end / density).toInt().coerceIn(1, 2048)
                    for (i in 0..samples) {
                        val x = end * i / samples
                        val y = sin((x / (20f * density) - phase) * (2 * PI)).toFloat() *
                            min(1.5f * density, height / 8f)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, paint)
                } else if (comet) {
                    path.rewind()
                    path.moveTo(0f, 0f)
                    path.cubicTo(end * .45f, 0f, end * .72f, -h / 2f, end, -h / 2f)
                    path.lineTo(end, h / 2f)
                    path.cubicTo(end * .72f, h / 2f, end * .45f, 0f, 0f, 0f)
                    path.close()
                    canvas.drawPath(path, paint)
                } else canvas.drawRoundRect(0f, -h / 2f, end, h / 2f, radius, radius, paint)
            }
            paint.style = Paint.Style.FILL
            val buttonHalfHeight = min(8f * density, height / 2f)
            val circleRadius = minOf(5f * density, height / 2f, width / 2f)
            val halfWidth = min(if (thumb == 1) density else 2f * density, width / 2f)
            val inset = if (thumb == 0) circleRadius else halfWidth
            val buttonX = end.coerceIn(inset, width - inset)
            when {
                thumb == 0 -> canvas.drawCircle(buttonX, 0f, circleRadius, paint)
                thumb == 1 -> canvas.drawRoundRect(buttonX - halfWidth, -buttonHalfHeight,
                    buttonX + halfWidth, buttonHalfHeight, density, density, paint)
                round -> canvas.drawRoundRect(buttonX - halfWidth, -buttonHalfHeight,
                    buttonX + halfWidth, buttonHalfHeight, 2f * density, 2f * density, paint)
            }
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}
