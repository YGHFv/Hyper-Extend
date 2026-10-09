/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.View
import android.content.res.Configuration
import io.github.YGHFv.HyperExtend.hook.Reflect

/** Only primitives cross the permission-protected launch bridge; never load plugin code in MiSound. */
internal object NativeVolumePanelStyle {
    private const val EXTRA = "io.github.YGHFv.HyperExtend.VOLUME_PANEL_STYLE"
    private const val PREFIX = "com.android.systemui.miui.volume."

    fun displayMetrics(view: View): DisplayMetrics = DisplayMetrics().also {
        @Suppress("DEPRECATION")
        (view.display ?: view.context.getSystemService(android.view.WindowManager::class.java).defaultDisplay).getRealMetrics(it)
    }

    /** Resource-only fallback for a native launch without an entry snapshot. No foreign class loader. */
    fun fallback(view: View): AppVolumePanelStyle? = runCatching {
        val context = view.context.createPackageContext("miui.systemui.plugin", 0)
            .createConfigurationContext(view.resources.configuration)
        val resources = context.resources
        fun dimen(name: String) = resources.getDimensionPixelSize(resources.getIdentifier(name, "dimen", "miui.systemui.plugin"))
        val metrics = displayMetrics(view)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val top = if (landscape) (metrics.heightPixels - dimen("o3_miui_volume_background_height_expanded")) / 2
            else dimen("o3_miui_volume_background_margin_top_expanded")
        val sourceWidth = dimen("miui_volume_column_width")
        val sourceHeight = dimen("miui_volume_column_height")
        val sourceTop = if (landscape) (metrics.heightPixels - sourceHeight) / 2
            else dimen("o3_miui_volume_background_margin_top")
        @Suppress("DEPRECATION")
        val display = view.display ?: view.context.getSystemService(android.view.WindowManager::class.java).defaultDisplay
        val position = AppVolumeSpring(.9, .3)
        AppVolumePanelStyle(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, display.rotation, display.displayId,
            top, dimen("miui_volume_offset_end_expand"), dimen("miui_volume_background_padding"), dimen("o3_miui_volume_bg_radius"),
            dimen("o3_miui_volume_expend_width"), dimen("o3_miui_volume_expend_height"),
            dimen("miui_volume_column_margin_horizontal_expanded"),
            metrics.widthPixels - dimen("miui_volume_offset_end") - sourceWidth, sourceTop, sourceWidth, sourceHeight,
            listOf(AppVolumeSpring(.82, .4), position, position, position, position, position)).takeIf { it.valid() }
    }.getOrNull()

    fun capture(dialog: ViewGroup): AppVolumePanelStyle? = runCatching {
        val motion = checkNotNull(Reflect.readField(dialog, "mMotion"))
        val context = Reflect.readField(motion, "mContext") as Context
        val loader = motion.javaClass.classLoader!!
        val res = Class.forName(PREFIX + "MiuiVolumeDialogRes", false, loader)
        val column = Class.forName(PREFIX + "VolumeColumnRes", false, loader)
        val bool = Boolean::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        fun value(name: String, vararg flags: Boolean): Int = res.getDeclaredMethod(name,
            Context::class.java, *Array(flags.size) { bool }).invoke(null, context, *flags.toTypedArray()) as Int
        val metrics = Reflect.callWith(motion, "provideDisplayMetrics") as DisplayMetrics
        val top = res.getDeclaredMethod("getMarginTop", Context::class.java, bool, bool, int, int)
            .invoke(null, context, true, true, metrics.heightPixels, value("getHeight", true, true)) as Int
        val right = value("getMarginRight", true, true) + (Reflect.callWith(motion, "getInsetRight") as Int)
        val single = Class.forName(PREFIX + "Util", false, loader).getDeclaredField("sIsNotificationSingle")
            .apply { isAccessible = true }.getBoolean(null)
        val width = column.getDeclaredMethod("getWidth", Context::class.java, bool, bool, bool)
            .invoke(null, context, true, true, single) as Int
        val height = column.getDeclaredMethod("getHeight", Context::class.java, bool, bool)
            .invoke(null, context, true, true) as Int
        val gap = context.resources.getDimensionPixelSize(context.resources.getIdentifier(
            "miui_volume_column_margin_horizontal_expanded", "dimen", "miui.systemui.plugin"))
        val source = Reflect.readField(motion, "mDialogContentView") as android.view.View
        val location = IntArray(2).also(source::getLocationOnScreen)
        val ease = Class.forName("miui.systemui.animation.FolmeUtilsExtKt", false, loader)
        val springs = listOf("EXPAND_SIZE", "EXPAND_POSITION", "EXPAND_COLOR", "COLLAPSE_SIZE", "COLLAPSE_POSITION", "COLLAPSE_COLOR").map {
            val style = ease.getDeclaredMethod("getEASE_${it}_LAYERED").invoke(null)!!
            check((Reflect.readField(style, "style") as Int) == -2)
            val factors = Reflect.readField(style, "factors") as DoubleArray
            AppVolumeSpring(factors[0], factors[1])
        }
        AppVolumePanelStyle(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            dialog.display.rotation, dialog.display.displayId, top, right, value("getBgWithContentPadding", true),
            value("getBgRadius"), width, height, gap, location[0], location[1], source.width, source.height, springs)
            .takeIf { it.valid() }
    }.getOrNull()

    fun put(intent: Intent, style: AppVolumePanelStyle): Intent = intent.putExtra(EXTRA, Bundle().apply {
        putInt("version", 1)
        putIntArray("geometry", with(style) { intArrayOf(screenWidth, screenHeight, densityDpi, rotation, displayId,
            top, right, padding, radius, sliderWidth, sliderHeight, gap, sourceLeft, sourceTop, sourceWidth, sourceHeight) })
        putDoubleArray("springs", style.springs.flatMap { listOf(it.damping, it.response) }.toDoubleArray())
    })

    fun read(intent: Intent): AppVolumePanelStyle? = runCatching {
        val bundle = intent.getBundleExtra(EXTRA) ?: return null
        if (bundle.getInt("version") != 1) return null
        val g = bundle.getIntArray("geometry") ?: return null
        val s = bundle.getDoubleArray("springs") ?: return null
        if (g.size != 16 || s.size != 12) return null
        AppVolumePanelStyle(g[0], g[1], g[2], g[3], g[4], g[5], g[6], g[7], g[8], g[9], g[10], g[11],
            g[12], g[13], g[14], g[15], s.toList().chunked(2).map { AppVolumeSpring(it[0], it[1]) }).takeIf { it.valid() }
    }.getOrNull()
}
