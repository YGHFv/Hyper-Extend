/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui


import android.animation.ArgbEvaluator
import android.content.res.Configuration
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
    /**
     * 按卡片的「处理方式」装**一条**路径（外观 / 隐藏 / 几何三选一）。
     *
     * 三条路径都作用在底部手势导航区，同时装只会互相覆盖（外观刚把半径改成用户值，
     * 隐藏就把绘制整个短路掉；几何又改同一 View 的高度），所以是分支而不是各自独立判断。
     * 隐藏路径复用 [GestureLineHider]，它自己还带「拦截绘制 / 按原生隐藏处理」两个子项。
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        return when (NavigationHandleSettings.modeOf(settings.string(NavigationHandleSettings.MODE))) {
            NavigationHandleSettings.MODE_HIDE ->
                io.github.YGHFv.HyperExtend.hook.feature.GestureLineHider.install(loader, settings)
            NavigationHandleSettings.MODE_GEOMETRY -> NavigationGeometryHooks.install(loader, settings)
            NavigationHandleSettings.MODE_NATIVE -> NavigationBarHideHooks.install(loader, settings)
            else -> installAppearance(loader, settings)
        }
    }

    private fun installAppearance(loader: ClassLoader, settings: HookSettings): Int {
        val specs = NavigationHandleTargets
        val methods = specs.entries.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val callers = specs.callers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        val owner = methods.first().declaringClass
        val fields = specs.fields.mapValues { (name, type) ->
            owner.getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
        }
        val pressOwner = Class.forName(specs.PRESS, false, loader)
        val pressFields = specs.pressFields.mapValues { (name, type) ->
            pressOwner.getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
        }
        // Geometry and exported transition info must see the same scoped values, including
        // previously inlined getter bodies. Partial installation never changes a live view.
        if (!(callers + methods).all { HookRuntime.deoptimize(it, "navigation_handle_custom/${it.name}") }) return 0
        val ready = AtomicBoolean(false)
        val radiusDp = NavigationHandleSettings.radiusDp(settings.string(NavigationHandleSettings.RADIUS))
        val bottomDp = NavigationHandleSettings.bottomDp(settings.string(NavigationHandleSettings.BOTTOM))
        val portraitWidth = NavigationHandleSettings.widthDp(settings.string(NavigationHandleSettings.WIDTH_PORTRAIT))
        val landscapeWidth = NavigationHandleSettings.widthDp(settings.string(NavigationHandleSettings.WIDTH_LANDSCAPE))
        val lightBackground = SystemUiCustomSettings.color(settings.string(NavigationHandleSettings.LIGHT_BACKGROUND), true)
        val darkBackground = SystemUiCustomSettings.color(settings.string(NavigationHandleSettings.DARK_BACKGROUND), true)
        val evaluator = ArgbEvaluator()
        val installed = methods.count { method ->
            HookRuntime.hook(method, "navigation_handle_custom/${method.name}") { chain ->
                if (!ready.get() || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                val view = chain.thisObject as? View ?: return@hook chain.proceed()
                if (view.display?.displayId != 0) return@hook chain.proceed()
                if (!NavigationHandlePolicy.supported(true) ||
                    !settings.isOn(NavigationHandleSettings.FEATURE)) return@hook chain.proceed()
                val radius = NavigationHandlePolicy.radiusPixels(radiusDp, view.resources.displayMetrics.density)
                    ?: return@hook chain.proceed()
                val press = fields.getValue("mPressAnimator").get(view) ?: return@hook chain.proceed()
                // 半径 0 表示「保留原生半径」：以前 0 被用来隐藏绘制，那条路径已并入隐藏方式。
                // 尺寸换算要用实际生效的半径（用户值或原生值），否则 0 会让脉冲空间算错。
                val nativeRadius = fields.getValue("mRadius").getFloat(view)
                val effectiveRadius = if (radius == 0f) nativeRadius else radius
                if (!effectiveRadius.isFinite() || effectiveRadius < 0f) return@hook chain.proceed()
                val dimensions = NavigationHandlePolicy.dimensions(
                    if (view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) landscapeWidth else portraitWidth,
                    bottomDp, view.resources.displayMetrics.density, view.width, view.height, effectiveRadius,
                    fields.getValue("mPulseAnimationProgress").getFloat(view),
                    fields.getValue("mAdditionalWidthForAnimation").getFloat(view),
                    fields.getValue("mAdditionalHeightForAnimation").getFloat(view),
                    pressFields.getValue("mWidthScale").getFloat(press),
                    pressFields.getValue("mHeightScale").getFloat(press)) ?: return@hook chain.proceed()
                val radiusField = fields.getValue("mRadius")
                val bottomField = fields.getValue("mBottom")
                val widthField = fields.getValue("mHandleWidth")
                val dirtyField = fields.getValue("mPillGeometryDirty")
                val lastRadiusField = fields.getValue("mLastPillRadius")
                val rect = fields.getValue("mLastPillRect").get(view) as RectF
                val paint = fields.getValue("mPaint").get(view) as Paint
                val intensity = fields.getValue("mDarkIntensity").getFloat(view)
                if (!intensity.isFinite() || intensity !in 0f..1f) return@hook chain.proceed()
                val originalRadius = radiusField.getFloat(view)
                val originalBottom = bottomField.getFloat(view)
                val originalWidth = widthField.getFloat(view)
                val originalDirty = dirtyField.getBoolean(view)
                val lastRadius = lastRadiusField.getFloat(view)
                val left = rect.left; val top = rect.top; val right = rect.right; val bottom = rect.bottom
                val originalColor = paint.color
                val customColor = if (lightBackground == null && darkBackground == null) null else evaluator.evaluate(
                    intensity, darkBackground ?: fields.getValue("mLightColor").getInt(view),
                    lightBackground ?: fields.getValue("mDarkColor").getInt(view)) as Int
                withNavigationHandleOverride(prepare = {
                    // 0 保留原生半径；只有用户给了非零值才覆盖，避免把宿主算好的半径抹掉。
                    if (radius != 0f) radiusField.setFloat(view, radius)
                    dimensions.bottom?.let { bottomField.setFloat(view, it) }
                    dimensions.width?.let { widthField.setFloat(view, it) }
                    dirtyField.setBoolean(view, true)
                    if (customColor != null) paint.color = customColor
                }, restore = {
                    radiusField.setFloat(view, originalRadius)
                    if (dimensions.bottom != null) bottomField.setFloat(view, originalBottom)
                    if (dimensions.width != null) widthField.setFloat(view, originalWidth)
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
