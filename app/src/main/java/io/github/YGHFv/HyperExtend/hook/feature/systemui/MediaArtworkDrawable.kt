/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.TargetApi
import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View

/** Owns only a module-generated bitmap; never recycles or mutates host artwork. */
@TargetApi(26)
internal class MediaArtworkDrawable(pixels: IntArray, private val mode: Int, private val radius: Float) : Drawable() {
    private val frames = MediaArtworkTransition(pixels)
    private var bitmap = bitmap(pixels)
    private var previous: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val artwork = RectF()
    private var shade: Shader? = null
    private var opacity = 255

    fun update(pixels: IntArray, animate: Boolean) {
        frames.update(pixels, SystemClock.uptimeMillis(), animate && canAnimate())
        previous = frames.previous?.let(::bitmap)
        bitmap = bitmap(pixels)
        invalidateSelf()
    }

    fun finishTransition() { frames.finish(); previous = null }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) finishTransition()
        return super.setVisible(visible, restart)
    }

    private fun canAnimate(): Boolean = isVisible && ValueAnimator.areAnimatorsEnabled() &&
        (callback as? View)?.let { it.isAttachedToWindow && it.isShown && it.windowVisibility == View.VISIBLE } == true

    private fun bitmap(pixels: IntArray) = Bitmap.createBitmap(pixels, MediaBackgroundPolicy.SIZE,
        MediaBackgroundPolicy.SIZE, Bitmap.Config.ARGB_8888)
    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val size = maxOf(rect.width(), rect.height())
        artwork.set(rect.centerX() - size / 2, rect.centerY() - size / 2, rect.centerX() + size / 2, rect.centerY() + size / 2)
        shade = when {
            size <= 0 -> null
            mode == 3 -> RadialGradient(rect.right, rect.centerY(), size, intArrayOf(0x00000000, 0xe6000000.toInt()), null, Shader.TileMode.CLAMP)
            mode == 4 -> LinearGradient(rect.left, 0f, rect.right, 0f, intArrayOf(0xe6000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
            else -> null
        }
    }
    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.clipPath(path)
        // Apply host alpha to the composed card, not twice to overlapping artwork layers.
        if (opacity < 255) canvas.saveLayerAlpha(rect, opacity)
        if (!canAnimate()) finishTransition()
        val alpha = frames.alpha(SystemClock.uptimeMillis())
        if (alpha == 255) finishTransition()
        paint.shader = null; paint.alpha = 255
        previous?.let { canvas.drawBitmap(it, null, artwork, paint) }
        paint.alpha = alpha
        canvas.drawBitmap(bitmap, null, artwork, paint)
        paint.alpha = 255
        shade?.let { paint.shader = it; canvas.drawRect(rect, paint); paint.shader = null }
        canvas.restoreToCount(save)
        if (previous != null) invalidateSelf()
    }
    override fun getOutline(outline: Outline) = outline.setRoundRect(bounds, radius)
    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun getAlpha() = opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
