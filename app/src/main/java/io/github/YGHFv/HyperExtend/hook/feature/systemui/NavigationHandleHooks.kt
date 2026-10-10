/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.animation.ArgbEvaluator
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.NavigationHandleSettings
import io.github.YGHFv.HyperExtend.core.SystemUiCustomSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.concurrent.atomic.AtomicBoolean

internal object NavigationHandleHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val specs = NavigationHandleTargets
        val methods = specs.entries.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val callers = specs.callers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val owner = methods.first().declaringClass
        val fields = specs.fields.mapValues { (name, type) ->
            owner.getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
        }
        // Geometry and exported transition info must see the same scoped values, including
        // previously inlined getter bodies. Partial installation never changes a live view.
        if (!(callers + methods).all { HookRuntime.deoptimize(it, "navigation_handle_custom/${it.name}") }) return 0
        val ready = AtomicBoolean(false)
        val radiusDp = NavigationHandleSettings.radiusDp(settings.string(NavigationHandleSettings.RADIUS))
        val lightBackground = SystemUiCustomSettings.color(settings.string(NavigationHandleSettings.LIGHT_BACKGROUND), true)
        val darkBackground = SystemUiCustomSettings.color(settings.string(NavigationHandleSettings.DARK_BACKGROUND), true)
        val evaluator = ArgbEvaluator()
        var version: Long? = null
        val installed = methods.count { method ->
            HookRuntime.hook(method, "navigation_handle_custom/${method.name}") { chain ->
                if (!ready.get() || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                val hostVersion = version ?: runCatching {
                    view.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode
                }.getOrNull()?.also { version = it }
                if (!NavigationHandlePolicy.supported(hostVersion, true, settings.isOn("gesture_line.skip_draw")) ||
                    !settings.isOn(NavigationHandleSettings.FEATURE)) return@hook chain.proceed()
                val radius = NavigationHandlePolicy.radiusPixels(radiusDp, view.resources.displayMetrics.density)
                    ?: return@hook chain.proceed()
                // Zero hides the drawing only. Keep native transition metadata and all input.
                if (radius == 0f) return@hook if (method.name == specs.draw.name) null else chain.proceed()
                val radiusField = fields.getValue("mRadius")
                val dirtyField = fields.getValue("mPillGeometryDirty")
                val lastRadiusField = fields.getValue("mLastPillRadius")
                val rect = fields.getValue("mLastPillRect").get(view) as RectF
                val paint = fields.getValue("mPaint").get(view) as Paint
                val intensity = fields.getValue("mDarkIntensity").getFloat(view)
                if (!intensity.isFinite() || intensity !in 0f..1f) return@hook chain.proceed()
                val originalRadius = radiusField.getFloat(view)
                val originalDirty = dirtyField.getBoolean(view)
                val lastRadius = lastRadiusField.getFloat(view)
                val left = rect.left; val top = rect.top; val right = rect.right; val bottom = rect.bottom
                val originalColor = paint.color
                val customColor = if (lightBackground == null && darkBackground == null) null else evaluator.evaluate(
                    intensity, darkBackground ?: fields.getValue("mLightColor").getInt(view),
                    lightBackground ?: fields.getValue("mDarkColor").getInt(view)) as Int
                withNavigationHandleOverride(prepare = {
                    radiusField.setFloat(view, radius)
                    dirtyField.setBoolean(view, true)
                    if (customColor != null) paint.color = customColor
                }, restore = {
                    radiusField.setFloat(view, originalRadius)
                    rect.set(left, top, right, bottom)
                    lastRadiusField.setFloat(view, lastRadius)
                    dirtyField.setBoolean(view, originalDirty)
                    if (customColor != null && paint.color == customColor) paint.color = originalColor
                }) { chain.proceed() }
            }
        }
        ready.set(installed == methods.size)
        return if (ready.get()) installed else 0
    }
}
