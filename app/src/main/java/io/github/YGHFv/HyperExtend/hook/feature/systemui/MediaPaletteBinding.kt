/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.res.ColorStateList
import android.annotation.TargetApi
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

@TargetApi(29)
internal class MediaPaletteAccess(private val loader: ClassLoader) {
    val fields = MediaPaletteTargets.fields.associate { (owner, name, type) ->
        (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
            require(this.type.name == type); isAccessible = true
        }
    }
    val methods = MediaPaletteTargets.methods.associate { it.name to requireNotNull(NotificationHooks.resolve(loader, it)) }
    fun field(owner: String, name: String) = fields.getValue(owner to name)
    class ShaderTint(color: Int) {
        val filter = BlendModeColorFilter(color, BlendMode.MODULATE)
    }
    val shaderTints = WeakHashMap<Any, ShaderTint>()
    @Volatile var ready = false

    fun installShaderHook(): Boolean {
        val draw = NotificationHooks.resolve(loader, MediaPaletteTargets.shaderDraw) ?: return false
        val caller = NotificationHooks.resolve(loader, MediaProgressTargets.draw) ?: return false
        if (!HookRuntime.deoptimize(caller, "media_palette/shaderCaller")) return false
        val paintField = field(MediaProgressTargets.BAR, "mPaint")
        return HookRuntime.hook(draw, "media_palette/nativeShaderTint") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            if (!ready || SafeModeRuntime.blocked || Looper.myLooper() != Looper.getMainLooper() ||
                !view.isAttachedToWindow || view.display?.displayId != 0) return@hook chain.proceed()
            val tint = shaderTints[view] ?: return@hook chain.proceed()
            val paint = paintField.get(view) as? Paint ?: return@hook chain.proceed()
            // Keep the complete native shader, texture, disabled/press alpha and geometry.
            // MODULATE tints native output without replacing its alpha mask or head texture.
            val original = paint.colorFilter
            if (original != null) return@hook chain.proceed()
            val filter = tint.filter
            withMediaPaletteOverride({ paint.colorFilter = filter },
                { if (paint.colorFilter === filter) paint.colorFilter = original }) { chain.proceed() }
        }
    }
}

/** Values contain only weak view references; restore never overwrites another writer's color. */
internal class MediaPaletteBinding(private val access: MediaPaletteAccess) {
    private class Color(val read: () -> ColorStateList?, val write: (ColorStateList?) -> Unit,
        val original: ColorStateList?, var owned: ColorStateList? = null)
    private class Number(val view: WeakReference<Any>, val field: Field, val method: Method,
        val original: Int, var owned: Int? = null)
    private val colors = mutableListOf<Pair<Color, Boolean>>()
    private val numbers = mutableListOf<Number>()
    private var owner = WeakReference<Any>(null)
    private var shaderView = WeakReference<Any>(null)
    private var shaderTint: MediaPaletteAccess.ShaderTint? = null
    private var last: MediaPalette? = null

    fun restore() {
        shaderView.get()?.let { if (access.shaderTints[it] === shaderTint) access.shaderTints.remove(it) }
        shaderView.clear(); shaderTint = null
        for ((color, _) in colors) if (color.owned != null && color.read() === color.owned) color.write(color.original)
        for (number in numbers) number.view.get()?.let {
            if (number.owned == number.field.getInt(it)) number.method.invoke(it, number.original)
        }
        colors.clear(); numbers.clear(); owner.clear(); last = null
    }

    fun apply(holder: Any, palette: MediaPalette) {
        if (owner.get() !== holder) { restore(); capture(holder) }
        if (palette == last) return
        shaderView.get()?.let {
            val tint = MediaPaletteAccess.ShaderTint(palette.primary)
            shaderTint = tint
            access.shaderTints[it] = tint
        }
        for ((color, secondary) in colors) {
            if (color.owned != null && color.read() !== color.owned) continue
            color.owned = ColorStateList.valueOf(if (secondary) palette.secondary else palette.primary)
            color.write(color.owned)
        }
        val values = listOf(palette.primary, MediaPalettePolicy.withAlpha(palette.primary, 97),
            MediaPalettePolicy.withAlpha(palette.primary, 46), MediaPalettePolicy.withAlpha(palette.primary, 25))
        numbers.forEachIndexed { index, number -> number.view.get()?.let {
            if (number.owned == null || number.field.getInt(it) == number.owned) {
                number.method.invoke(it, values[index]); number.owned = values[index]
            }
        } }
        last = palette
    }

    private fun capture(holder: Any) {
        owner = WeakReference(holder)
        for (name in listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView")) {
            val text = access.field(SystemUiTargets.MEDIA_HOLDER, name).get(holder) as? TextView ?: continue
            val weak = WeakReference(text)
            colors += Color({ weak.get()?.textColors }, { value -> if (value != null) weak.get()?.setTextColor(value) }, text.textColors) to (name != "titleText")
        }
        val actions = access.methods.getValue("getActionList").invoke(holder) as? List<*> ?: emptyList<Any>()
        for (image in actions + access.field(SystemUiTargets.MEDIA_HOLDER, "seamlessIcon").get(holder)) {
            if (image !is ImageView) continue
            val weak = WeakReference(image)
            colors += Color({ weak.get()?.imageTintList }, { weak.get()?.imageTintList = it }, image.imageTintList) to false
        }
        val seek = access.field(SystemUiTargets.MEDIA_HOLDER, "seekBar").get(holder) ?: return
        val level = access.field(MediaProgressTargets.BAR, "mDeviceLevel").getInt(seek)
        if (level in 1..2) shaderView = WeakReference(seek)
        // Level 1 has both primary and shader passes; level 2 ignores primary setters.
        if (level !in 0..1) return
        for (suffix in listOf("ForegroundPrimaryColor", "ForegroundPrimaryDisableColor", "BackgroundPrimaryColor", "BackgroundPrimaryDisableColor")) {
            val field = access.field(MediaProgressTargets.BAR, "m$suffix")
            numbers += Number(WeakReference(seek), field, access.methods.getValue("set$suffix"), field.getInt(seek))
        }
    }
}
