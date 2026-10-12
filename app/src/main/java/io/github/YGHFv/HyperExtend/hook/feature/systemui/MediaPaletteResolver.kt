/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.graphics.Bitmap

internal data class MediaArtworkResult(val pixels: IntArray, val palette: MediaPalette?,
    val ambientLight: Int, val ambientDark: Int)

/** Reflection resolution is inert. Monet instances are created only for eligible bound artwork. */
// 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
@SuppressLint("NewApi")
internal class MediaPaletteResolver(loader: ClassLoader) {
    private val scheme = Class.forName(MediaPaletteTargets.SCHEME, false, loader)
    private val tonal = Class.forName(MediaPaletteTargets.TONAL, false, loader)
    private val constructor = scheme.getDeclaredConstructor(WallpaperColors::class.java,
        Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
    private val fields = listOf("mAccent1", "mAccent2", "mNeutral1", "mNeutral2").map { name ->
        scheme.getDeclaredField(name).apply { require(type == tonal); isAccessible = true }
    }
    private val shades = tonal.getDeclaredField("allShades").apply {
        require(type == List::class.java); isAccessible = true
    }

    fun resolve(pixels: IntArray, mode: Int, inverse: Boolean, optimizedAmbient: Boolean): MediaArtworkResult? {
        val dominant = MediaPalettePolicy.dominant(pixels) ?: return null
        if (mode == 0 && !optimizedAmbient) return MediaArtworkResult(pixels, null, dominant, dominant)
        val bitmap = Bitmap.createBitmap(pixels, MediaBackgroundPolicy.SIZE, MediaBackgroundPolicy.SIZE, Bitmap.Config.ARGB_8888)
        val colors = try { WallpaperColors.fromBitmap(bitmap) } finally { bitmap.recycle() }
        // Current OS4 switch value 6 creates SchemeContent; the old Style enum is absent.
        val instance = constructor.newInstance(colors, true, 6)
        val palettes = fields.map { field ->
            val list = shades.get(field.get(instance)) as? List<*> ?: return null
            if (list.size != 13 || list.any { it !is Int }) return null
            list.map { it as Int }
        }
        val palette = if (mode == 0) null else MediaPalettePolicy.colors(mode, inverse,
            MediaPalettePolicy.brightness(pixels) ?: return null, palettes[0], palettes[1], palettes[2], palettes[3]) ?: return null
        return MediaArtworkResult(pixels, palette, palettes[0][4], palettes[0][7])
    }
}
