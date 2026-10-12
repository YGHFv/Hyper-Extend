/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.sin

/** Overlay only: no replacement/clearing of the original background or material. */
internal class MediaAmbientDrawable(private val radius: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var color = 0
    private var light = false
    private var phase = 0f
    private var opacity = 255

    fun frame(nextColor: Int, lightMode: Boolean, nextPhase: Float) {
        if (color == nextColor && light == lightMode && phase == nextPhase) return
        color = nextColor; light = lightMode; phase = nextPhase
        invalidateSelf()
    }
    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds); path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
    }
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(path)
        for (index in 0..2) {
            val x = rect.left + rect.width() * (0.2f + index * 0.3f + 0.1f * sin(phase + index * 2f))
            val y = rect.top + rect.height() * (0.35f + 0.2f * sin(phase * 0.7f + index))
            val alpha = (if (light) 45 else 78) * opacity / 255
            paint.shader = RadialGradient(x, y, maxOf(rect.width(), rect.height()) * 0.65f,
                MediaPalettePolicy.withAlpha(color, alpha), MediaPalettePolicy.withAlpha(color, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(rect, paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
