/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.annotation.TargetApi
import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@TargetApi(26)
internal class MediaArtworkJob {
    private val generation = MediaArtworkGeneration()
    private var task: FutureTask<Unit>? = null
    fun cancel() {
        generation.next()
        task?.let { it.cancel(false); worker.remove(it) }
        task = null
    }

    fun submit(snapshot: IntArray, mode: Int, blur: Int, publish: (IntArray?) -> Unit) {
        cancel()
        val token = generation.next()
        task = FutureTask<Unit> {
            if (!generation.accepts(token)) return@FutureTask Unit
            val pixels = runCatching {
                val size = MediaBackgroundPolicy.SIZE
                val input = if (mode == 1) IntArray(snapshot.size) { index ->
                    // Deterministic mirrored tiles, not a new random composition on each bind.
                    val x = index % size; val y = index / size
                    val sx = if (x < size / 2) x * 2 else (size - 1 - x) * 2
                    val sy = if (y < size / 2) y * 2 else (size - 1 - y) * 2
                    snapshot[sy * size + sx]
                } else snapshot
                val radius = when (mode) { 1 -> 16; 2 -> MediaBackgroundPolicy.radius(blur, size); else -> 0 }
                MediaBackgroundPolicy.blur(input, size, size, radius).also { result ->
                    for (i in result.indices) result[i] = MediaBackgroundPolicy.darken(result[i])
                }
            }.getOrNull()
            if (generation.accepts(token)) main.post {
                if (generation.accepts(token)) { task = null; publish(pixels) }
            }
            Unit
        }
        // One running and at most four pending thumbnails, even with unusual host bindings.
        try { worker.execute(task!!) } catch (_: RejectedExecutionException) { cancel(); publish(null) }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val worker = ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(4)).apply { allowCoreThreadTimeOut(true) }

        fun snapshot(source: Drawable): IntArray? = runCatching {
            val size = MediaBackgroundPolicy.SIZE
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output); canvas.drawColor(Color.BLACK)
                val bitmap = (source as? BitmapDrawable)?.bitmap
                if (bitmap != null) {
                    if (bitmap.isRecycled) return null
                    // A software canvas cannot draw a hardware bitmap; only copy bounded inputs.
                    val copy = if (bitmap.config == Bitmap.Config.HARDWARE) {
                        if (bitmap.width.toLong() * bitmap.height > 4_194_304) return null
                        bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
                    } else bitmap
                    try {
                        val edge = minOf(copy.width, copy.height)
                        val x = (copy.width - edge) / 2; val y = (copy.height - edge) / 2
                        canvas.drawBitmap(copy, Rect(x, y, x + edge, y + edge), RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                    } finally { if (copy !== bitmap) copy.recycle() }
                } else {
                    val copy = source.constantState?.newDrawable()?.mutate() ?: return null
                    copy.setBounds(0, 0, size, size); copy.draw(canvas)
                }
                IntArray(size * size).also { output.getPixels(it, 0, size, 0, 0, size, size) }
            } finally { output.recycle() }
        }.getOrNull()
    }
}
