/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.CardTileColorSettings
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object CardTileColorHooks {
    private data class Color(val native: ColorStateList, val applied: Int)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = CardTileColorTargets
        val id = CardTileColorSettings.FEATURE
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, expected) ->
            (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        fun field(owner: String, name: String) = fields.getValue(owner to name)
        fun singleton(owner: String, name: String, expected: String) =
            Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && Modifier.isStatic(modifiers)); isAccessible = true
            }
        val companion = singleton("miui.systemui.controlcenter.qs.tileview.QSItemView", "Companion", t.COMPANION)
        val theme = singleton(TileCornerTargets.THEME, "INSTANCE", TileCornerTargets.THEME)
        if (!methods.values.all { HookRuntime.deoptimize(it, "$id/caller") }) return 0
        val ready = AtomicBoolean(false)
        val texts = WeakHashMap<TextView, Color>()
        val backgrounds = WeakHashMap<GradientDrawable, Color>()
        val ownedBackgrounds = WeakHashMap<GradientDrawable, ColorStateList>()
        fun mainThread() = Looper.myLooper() == Looper.getMainLooper()
        fun allowed(view: View, state: Any?): Boolean = runCatching {
            if (!ready.get() || !mainThread() || SafeModeRuntime.blocked || !settings.isOn(id) || state == null)
                return@runCatching false
            StockTilesPolicy.supported() &&
                (view.display?.displayId ?: view.context.hostDisplayIdOrNull()) == 0 &&
                methods.getValue(TileCornerTargets.theme).invoke(theme.get(null)) == true &&
                methods.getValue(t.blend).invoke(view) == false &&
                TileColorPolicy.eligible(field(t.STATE, "state").getInt(state),
                    field(t.STATE, "activeBgColor").getInt(state), field(t.STATE, "disabledByPolicy").getBoolean(state),
                    field(t.STATE, "isTransient").getBoolean(state),
                    methods.getValue(t.restricted).invoke(companion.get(null), state) != false) &&
                methods.getValue(t.warning).invoke(companion.get(null), state) == false
        }.getOrDefault(false)
        fun textViews(view: View): List<TextView> {
            val binding = methods.getValue(t.binding).invoke(view) ?: return emptyList()
            return listOf("title", "status").mapNotNull { field(t.BINDING, it).get(binding) as? TextView }
        }
        fun restore(view: View) {
            (field(t.VIEW, "enabledBg").get(view) as? GradientDrawable)?.let { drawable ->
                backgrounds.remove(drawable)?.let { saved ->
                    if (TileColorPolicy.ownsColor(drawable.color?.defaultColor, drawable.color?.isStateful == true, saved.applied))
                        drawable.setColor(saved.native)
                }
            }
            if (texts.isNotEmpty()) {
                for (text in textViews(view)) texts.remove(text)?.let { saved ->
                    if (TileColorPolicy.ownsColor(text.textColors.defaultColor, text.textColors.isStateful, saved.applied))
                        text.setTextColor(saved.native)
                }
            }
        }
        fun applyText(view: View) {
            if (!allowed(view, field(t.VIEW, "state").get(view))) return
            val requested = SystemUiCustomSettings.color(settings.string(CardTileColorSettings.FOREGROUND)) ?: return
            for (text in textViews(view)) {
                val native = text.textColors.takeUnless { it.isStateful } ?: continue
                texts[text] = Color(native, requested)
                text.setTextColor(requested)
            }
        }
        fun applyBackground(view: View) {
            if (!allowed(view, field(t.VIEW, "state").get(view))) return
            val requested = SystemUiCustomSettings.color(settings.string(CardTileColorSettings.BACKGROUND)) ?: return
            val drawable = field(t.VIEW, "enabledBg").get(view) as? GradientDrawable ?: return
            val native = ownedBackgrounds[drawable] ?: return
            if (drawable.colorFilter != null || drawable.color != native) return
            backgrounds[drawable] = Color(native, requested)
            drawable.setColor(requested)
        }
        val backgroundHook = HookRuntime.hook(methods.getValue(t.enabled), "$id/enabledBackground") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            if (!allowed(view, field(t.VIEW, "state").get(view))) return@hook chain.proceed()
            val requested = SystemUiCustomSettings.color(settings.string(CardTileColorSettings.BACKGROUND))
                ?: return@hook chain.proceed()
            val native = chain.args[0] as? GradientDrawable ?: return@hook chain.proceed()
            if (native.javaClass != GradientDrawable::class.java || native.colorFilter != null) return@hook chain.proceed()
            val color = native.color?.takeUnless { it.isStateful } ?: return@hook chain.proceed()
            val copy = native.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
                as? GradientDrawable ?: return@hook chain.proceed()
            if (copy === native) return@hook chain.proceed()
            copy.bounds = native.bounds
            copy.setState(native.state)
            copy.setLevel(native.level)
            copy.alpha = native.alpha
            copy.setLayoutDirection(native.layoutDirection)
            copy.setVisible(native.isVisible, false)
            ownedBackgrounds[copy] = color
            backgrounds[copy] = Color(color, requested)
            copy.setColor(requested)
            // The host still builds its LayerDrawable and drives the enabled-layer alpha.
            chain.proceed(arrayOf<Any?>(copy))
        }
        val iconHook = HookRuntime.hook(methods.getValue(t.icon), "$id/iconInput") { chain ->
            val icon = chain.thisObject as? View ?: return@hook chain.proceed()
            var parent = icon.parent
            var card: View? = null
            repeat(4) {
                if (card == null && parent is View) {
                    val view = parent as View
                    if (view.javaClass.name == t.VIEW) card = view else parent = view.parent
                }
            }
            if (card == null || !allowed(card!!, chain.args[0])) return@hook chain.proceed()
            val requested = SystemUiCustomSettings.color(settings.string(CardTileColorSettings.FOREGROUND))
                ?: return@hook chain.proceed()
            val colors = listOf(field(t.ICON, "defaultIconColor"), field(t.ICON, "miuiCellularTileIconColor"))
            val saved = colors.map { it.getInt(icon) }
            try {
                colors.forEach { it.setInt(icon, requested) }
                chain.proceed()
            } finally {
                colors.forEachIndexed { index, color ->
                    if (color.getInt(icon) == requested) color.setInt(icon, saved[index])
                }
            }
        }
        val refresh = t.refresh.map { spec ->
            HookRuntime.hook(methods.getValue(spec), "$id/${spec.name}") { chain ->
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                if (!ready.get() || !mainThread()) return@hook chain.proceed()
                restore(view)
                val result = chain.proceed()
                // Normalize nested refreshes, then reapply even when the native same-state
                // path did not rebuild the drawable. Alpha/animation ownership stays native.
                restore(view)
                if (spec != t.recycle) {
                    applyBackground(view)
                    applyText(view)
                }
                result
            }
        }
        ready.set(backgroundHook && iconHook && refresh.all { it })
        return if (ready.get()) 2 + refresh.size else 0
    }
}
