package io.github.YGHFv.HyperExtend.hook.feature

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs

internal object NotificationIconCompat {
    private const val MAX_RENDER_SIZE = 256

    fun customAppIcon(
        drawable: Drawable?,
        resources: Resources,
        returnType: Class<*>,
        original: Any?,
    ): Any? = runCatching {
        if (returnType != Bitmap::class.java && !Drawable::class.java.isAssignableFrom(returnType)) {
            return@runCatching original
        }
        val bitmap = drawable?.let { toBitmap(it, resources) } ?: return@runCatching original
        val replacement = if (returnType == Bitmap::class.java) bitmap else BitmapDrawable(resources, bitmap)
        if (returnType.isInstance(replacement)) replacement else original
    }.getOrDefault(original)

    fun toBitmap(drawable: Drawable, resources: Resources): Bitmap? {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap?.takeUnless { it.isRecycled }
        }
        return runCatching {
            val fallbackSize = (resources.displayMetrics.densityDpi / 8).coerceIn(1, MAX_RENDER_SIZE)
            val sourceWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: fallbackSize
            val sourceHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: fallbackSize
            val scale = minOf(1.0, MAX_RENDER_SIZE.toDouble() / maxOf(sourceWidth, sourceHeight))
            val width = (sourceWidth * scale).toInt().coerceIn(1, MAX_RENDER_SIZE)
            val height = (sourceHeight * scale).toInt().coerceIn(1, MAX_RENDER_SIZE)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val previousBounds = Rect(drawable.bounds)
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                bitmap
            } catch (failure: Throwable) {
                bitmap.recycle()
                throw failure
            } finally {
                drawable.bounds = previousBounds
            }
        }.getOrNull()
    }

    fun isGrayscale(drawable: Drawable, resources: Resources): Boolean? {
        val bitmap = toBitmap(drawable, resources) ?: return null
        var sampled: Bitmap? = null
        return try {
            val pixelsBitmap = if (bitmap.width > 24 || bitmap.height > 24) {
                Bitmap.createScaledBitmap(bitmap, 24, 24, true).also { sampled = it }
            } else bitmap
            val pixels = IntArray(pixelsBitmap.width * pixelsBitmap.height)
            pixelsBitmap.getPixels(pixels, 0, pixelsBitmap.width, 0, 0, pixelsBitmap.width, pixelsBitmap.height)
            pixels.all { pixel ->
                val alpha = (pixel ushr 24) and 0xff
                val red = (pixel ushr 16) and 0xff
                val green = (pixel ushr 8) and 0xff
                val blue = pixel and 0xff
                alpha < 50 || (abs(red - green) < 20 && abs(red - blue) < 20 && abs(green - blue) < 20)
            }
        } catch (_: Throwable) {
            null
        } finally {
            sampled?.takeIf { it !== bitmap }?.recycle()
            if (drawable !is BitmapDrawable || drawable.bitmap !== bitmap) bitmap.recycle()
        }
    }
}
