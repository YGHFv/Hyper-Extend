/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.VectorDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.content.res.ColorStateList
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.TileCornerSettings
import io.github.YGHFv.HyperExtend.core.TileColorSettings
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object TileCornerHooks {
    // ColorStateList contains no View or callback. Both features share the same owned copy.
    private data class Shape(var native: Float, var applied: Float? = null,
        var nativeColor: ColorStateList? = null, var appliedColor: Int? = null)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = TileCornerTargets
        val feature = TileCornerSettings.FEATURE
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, expected) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        val stateFields = t.stateFields.associate { (name, expected) ->
            name to Class.forName(t.STATE, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        val theme = Class.forName(t.THEME, false, loader).getDeclaredField("INSTANCE").apply {
            require(type.name == t.THEME && java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
        }.get(null)
        val constructor = Class.forName(t.VIEW, false, loader).getDeclaredConstructor(
            android.content.Context::class.java, android.content.Context::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        if (!(methods.values + constructor).map { HookRuntime.deoptimize(it, "$feature/caller") }.all { it }) return 0
        val ready = AtomicBoolean(false)
        // Never keep Drawables or Views in a weak-key value: callbacks can retain the key.
        val shapes = WeakHashMap<GradientDrawable, Shape>()
        var versions: Pair<Long, Long>? = null
        fun mainThread() = Looper.myLooper() == Looper.getMainLooper()
        fun eligible(view: View, id: String): Boolean = runCatching {
            if (!ready.get() || SafeModeRuntime.blocked || !mainThread() || !settings.isOn(id)) return@runCatching false
            val host = versions ?: view.context.packageManager.let { pm ->
                (pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode to
                    pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode).also { versions = it }
            }
            val display = view.display?.displayId ?: view.context.hostDisplayIdOrNull()
            if (!TileCornerPolicy.eligible(true, host.first, host.second, display,
                    fields.getValue("card").getBoolean(view), fields.getValue("isDetailTile").getBoolean(view),
                    methods.getValue(t.theme).invoke(theme) == true, methods.getValue(t.material).invoke(null, view.context) != false))
                return@runCatching false
            true
        }.getOrDefault(false)
        fun radius(view: View): Float? = if (eligible(view, feature))
            TileCornerPolicy.radius(TileCornerSettings.value(settings.string(TileCornerSettings.radius.key)),
                fields.getValue("tileSize").getFloat(view)) else null
        fun color(view: View, state: Any?, key: String = TileColorSettings.BACKGROUND): Int? = runCatching {
            if (state == null || !eligible(view, TileColorSettings.FEATURE)) return@runCatching null
            if (!TileColorPolicy.eligible(stateFields.getValue("state").getInt(state),
                    stateFields.getValue("activeBgColor").getInt(state), stateFields.getValue("disabledByPolicy").getBoolean(state),
                    stateFields.getValue("isTransient").getBoolean(state), methods.getValue(t.restricted).invoke(view) != false))
                return@runCatching null
            SystemUiCustomSettings.color(settings.string(key))
        }.getOrNull()
        fun clone(original: GradientDrawable, view: View): GradientDrawable? {
            if (original.javaClass != GradientDrawable::class.java || original.shape != GradientDrawable.RECTANGLE ||
                original.cornerRadii != null) return null
            val copy = original.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
                as? GradientDrawable ?: return null
            if (copy === original) return null
            copy.bounds = original.bounds
            copy.setState(original.state)
            copy.setLevel(original.level)
            copy.alpha = original.alpha
            copy.setLayoutDirection(original.layoutDirection)
            copy.setVisible(original.isVisible, false)
            copy.colorFilter = original.colorFilter
            return copy
        }
        fun backgrounds(view: View): List<GradientDrawable> = listOf("enabledBg", "disabledBg")
            .mapNotNull { fields.getValue(it).get(view) as? GradientDrawable }.distinct()
        fun outline(view: View) { (fields.getValue("icon").get(view) as View).invalidateOutline() }
        fun restore(view: View) {
            var changed = false
            for (drawable in backgrounds(view)) {
                val shape = shapes[drawable] ?: continue
                shape.applied?.let { applied ->
                    if (TileCornerPolicy.ownsRadius(drawable.cornerRadius, drawable.cornerRadii != null, applied)) {
                        drawable.cornerRadius = shape.native
                        changed = true
                    }
                    // A newer native/transition radius forfeits radius ownership, not color.
                    shape.applied = null
                    if (drawable.cornerRadius != shape.native || drawable.cornerRadii != null) shape.native = Float.NaN
                }
                shape.appliedColor?.let { applied ->
                    val current = drawable.color
                    if (TileColorPolicy.ownsColor(current?.defaultColor, current?.isStateful == true, applied))
                        drawable.setColor(shape.nativeColor)
                    else shape.nativeColor = null
                    shape.appliedColor = null
                }
            }
            if (changed) outline(view)
        }
        fun apply(view: View) {
            val requested = radius(view)
            val requestedColor = color(view, fields.getValue("state").get(view))
            var changed = false
            for (drawable in backgrounds(view)) {
                val shape = shapes[drawable] ?: continue
                if (requested != null && shape.native.isFinite() && drawable.cornerRadii == null) {
                    shape.native = drawable.cornerRadius
                    drawable.cornerRadius = requested
                    shape.applied = requested
                    changed = true
                }
                val currentColor = drawable.color
                if (requestedColor != null && shape.nativeColor != null && currentColor != null &&
                    !currentColor.isStateful && drawable.colorFilter == null) {
                    shape.nativeColor = currentColor
                    drawable.setColor(requestedColor)
                    shape.appliedColor = requestedColor
                }
            }
            if (changed) outline(view)
        }
        val activeBackground = HookRuntime.hookAfter(methods.getValue(t.activeBackground), "${TileColorSettings.FEATURE}/activeBackground") { chain, original ->
            val view = chain.thisObject as? View ?: return@hookAfter original
            val requested = color(view, chain.args[0]) ?: return@hookAfter original
            val native = original as? GradientDrawable ?: return@hookAfter original
            // Do not flatten a theme's stateful palette or bypass tint/filter semantics.
            val nativeColor = native.color?.takeUnless { it.isStateful } ?: return@hookAfter original
            if (native.colorFilter != null) return@hookAfter original
            val copy = clone(native, view) ?: return@hookAfter original
            shapes[copy] = Shape(Float.NaN, nativeColor = nativeColor, appliedColor = requested)
            copy.setColor(requested)
            copy
        }
        val tinting = ThreadLocal<View?>()
        val tint = HookRuntime.hook(methods.getValue(t.tint), "${TileColorSettings.FEATURE}/iconTint") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            if (tinting.get() === view) return@hook chain.proceed()
            val state = chain.args[0] ?: return@hook chain.proceed()
            val requested = color(view, state, TileColorSettings.ICON) ?: return@hook chain.proceed()
            if (!TileColorPolicy.genericIcon(stateFields.getValue("spec").get(state) as? String)) return@hook chain.proceed()
            val drawable = chain.args[1] ?: return@hook chain.proceed()
            if (drawable.javaClass != VectorDrawable::class.java && drawable.javaClass != AnimatedVectorDrawable::class.java)
                return@hook chain.proceed()
            val field = fields.getValue("defaultIconColor")
            val previous = tinting.get()
            tinting.set(view)
            try {
                // Let the native method mutate and tint its own vector; never replace an
                // animated vector or its callbacks. Only the per-view input is temporary.
                withTileIconColor(requested, { field.getInt(view) }, { field.setInt(view, it) }) { chain.proceed() }
            } finally { if (previous == null) tinting.remove() else tinting.set(previous) }
        }
        val setters = listOf(t.enabled, t.disabled).map { spec ->
            HookRuntime.hook(methods.getValue(spec), "$feature/${spec.name}") { chain ->
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                val requested = radius(view) ?: return@hook chain.proceed()
                val original = chain.args[0] as? GradientDrawable ?: return@hook chain.proceed()
                // Clone first, then mutate: never alter the resource's shared ConstantState.
                val copy = clone(original, view) ?: return@hook chain.proceed()
                val inherited = shapes[original]
                shapes[copy] = Shape(copy.cornerRadius, requested, inherited?.nativeColor, inherited?.appliedColor)
                copy.cornerRadius = requested
                chain.proceed(arrayOf<Any?>(copy))
            }
        }
        val refresh = t.refresh.map { spec ->
            HookRuntime.hook(methods.getValue(spec), "$feature/${spec.name}") { chain ->
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                if (!ready.get() || !mainThread()) return@hook chain.proceed()
                restore(view)
                val result = chain.proceed()
                if (spec == t.recycle) backgrounds(view).forEach { shapes.remove(it) }
                else {
                    // A nested native setter may already have installed a custom copy. Normalize
                    // it before recording the next baseline, including same-state early returns.
                    restore(view)
                    apply(view)
                }
                result
            }
        }
        ready.set(activeBackground && tint && (setters + refresh).all { it })
        return if (ready.get()) 2 + setters.size + refresh.size else 0
    }
}
