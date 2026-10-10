/*
 * Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0
 * Adapted MiSoundAppVolumeHook card, blur and animation behavior. Hooks are scoped
 * to the audited MiSound controller, not process-wide WindowManager methods.
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.function.Consumer
import androidx.annotation.RequiresApi

/** Only MiSound 260903; every obfuscated target is recorded in the evidence manifest. */
@RequiresApi(33)
internal object MiSoundCardHooks {
    private val cards = WeakHashMap<ViewGroup, WeakReference<Card>>()

    fun prepareOpen(root: View?, style: AppVolumePanelStyle?) {
        cards[root]?.get()?.prepare(style)
    }

    fun install(loader: ClassLoader, controller: Class<*>): Int {
        val adapter = Class.forName(controller.name + "\$i", false, loader)
        val holder = Class.forName(controller.name + "\$i\$a", false, loader)
        val callback = Class.forName(controller.name + "\$e", false, loader)
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val context = field(controller, "c")
        val ball = field(controller, "m")
        val page = field(controller, "o")
        val status = field(controller, "a")
        val handler = field(controller, "x")
        val pages = field(adapter, "e")
        val adapterController = field(adapter, "g")
        val item = field(holder, "e")
        val callbackController = field(callback, "a")
        val cleanup = controller.getDeclaredMethod("p").apply { isAccessible = true }
        val add = controller.getDeclaredMethod("C", View::class.java, WindowManager.LayoutParams::class.java)
        val bind = adapter.getDeclaredMethod("a", holder, Int::class.javaPrimitiveType)
        val create = adapter.getDeclaredMethod("b", ViewGroup::class.java, Int::class.javaPrimitiveType)
        val ctor = adapter.getDeclaredConstructor(controller, Context::class.java)
        val selected = callback.getDeclaredMethod("onPageSelected", Int::class.javaPrimitiveType)
        require(context.type == Context::class.java && status.type == Int::class.javaPrimitiveType)
        require(View::class.java.isAssignableFrom(ball.type) && ViewGroup::class.java.isAssignableFrom(page.type))
        require(Handler::class.java.isAssignableFrom(handler.type) && ViewGroup::class.java.isAssignableFrom(item.type))
        var enabled = false
        var audited: Boolean? = null
        fun supported(host: Any): Boolean {
            if (!enabled) return false
            return audited ?: ((context.get(host) as Context).packageManager
                .getPackageInfo(AppVolumeSettings.PACKAGE, 0).compatibleVersionCode == 260903L).also {
                audited = it
                if (!it) ModuleLog.warn("app_volume card: unaudited MiSound; native UI retained")
            }
        }
        fun card(host: Any): Card? {
            val root = page.get(host) as? ViewGroup ?: return null
            return cards[root]?.get() ?: Card.create(host, root)?.also { cards[root] = WeakReference(it) }
        }
        var count = 0
        if (HookRuntime.hook(add, "app_volume/card/window") { chain ->
                val host = chain.thisObject!!
                if (!supported(host)) return@hook chain.proceed()
                // Suppress only this controller's ball; retain its click listener/state machine.
                if (chain.args[0] === ball.get(host) && card(host) != null) return@hook null
                if (chain.args[0] === page.get(host)) {
                    card(host)?.let {
                        it.layout()
                        val lp = chain.args[1] as WindowManager.LayoutParams
                        lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_BLUR_BEHIND or
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND).inv()
                        lp.dimAmount = 0f
                        lp.setBlurBehindRadius(0)
                        lp.windowAnimations = 0
                    }
                }
                chain.proceed()
            }) count++
        for (name in listOf("n", "m", "y")) {
            if (HookRuntime.hookAfter(controller.getDeclaredMethod(name), "app_volume/card/$name") { chain, result ->
                    val host = chain.thisObject!!
                    if (supported(host)) card(host)?.let {
                        it.layout()
                        if (name == "y" && it.root.isAttachedToWindow && status.getInt(host) == 5000) it.show()
                    }
                    result
                }) count++
        }
        if (HookRuntime.hook(controller.getDeclaredMethod("g"), "app_volume/card/dismiss") { chain ->
                val host = chain.thisObject!!
                if (!supported(host) || status.getInt(host) != 5000) return@hook chain.proceed()
                val current = card(host) ?: return@hook chain.proceed()
                if (!current.root.isAttachedToWindow) return@hook chain.proceed()
                status.setInt(host, 301)
                current.hide { token ->
                    // Use native cleanup only after the morph finishes, never on an old generation.
                    Reflect.attempt {
                        if (current.transition.accepts(token) && status.getInt(host) == 301 && page.get(host) === current.root) cleanup.invoke(host)
                    }
                }
                null
            }) count++
        if (HookRuntime.hookAfter(ctor, "app_volume/card/pages") { chain, result ->
                val host = adapterController.get(chain.thisObject)!!
                if (supported(host)) {
                    val views = (pages.get(chain.thisObject) as List<*>).filterIsInstance<ViewGroup>()
                    card(host)?.setPages(views)
                }
                result
            }) count++
        if (HookRuntime.hookAfter(create, "app_volume/card/create") { chain, result ->
                if (supported(adapterController.get(chain.thisObject)!!)) {
                    (item.get(result) as ViewGroup).apply {
                        setOnClickListener(null)
                        isClickable = false
                        clipChildren = false
                    }
                }
                result
            }) count++
        if (HookRuntime.hook(bind, "app_volume/card/bind") { chain ->
                val host = adapterController.get(chain.thisObject)!!
                if (!supported(host)) return@hook chain.proceed()
                val target = item.get(chain.args[0]) as ViewGroup
                val bound = (pages.get(chain.thisObject) as List<*>).getOrNull(chain.args[1] as Int) as? View
                if (bound != null) {
                    (bound.parent as? ViewGroup)?.removeView(bound)
                    // A recycled holder must not retain its previous page behind the new one.
                    target.removeAllViews()
                }
                val result = chain.proceed()
                Reflect.attempt { card(host)?.layout() }
                result
            }) count++
        if (HookRuntime.hookAfter(selected, "app_volume/card/pageSelected") { chain, result ->
                val host = callbackController.get(chain.thisObject)!!
                if (supported(host)) card(host)?.layout()
                result
            }) count++
        // Partial hook installation must not leave the old entry hidden without a usable panel.
        enabled = count == 9
        if (enabled) {
            for (method in controller.declaredMethods) HookRuntime.deoptimize(method, "app_volume/card/controller")
            for (constructor in controller.declaredConstructors) HookRuntime.deoptimize(constructor, "app_volume/card/controllerCtor")
            for (method in adapter.declaredMethods) HookRuntime.deoptimize(method, "app_volume/card/adapter")
        } else ModuleLog.warn("app_volume card: incomplete hooks ($count/9); native UI retained")
        return count
    }

    private class Card(val root: ViewGroup, val body: LinearLayout, val pager: View, host: Any) : View.OnAttachStateChangeListener {
        private val controller = WeakReference(host)
        private var pages = emptyList<WeakReference<ViewGroup>>()
        private var columns = 1
        private var radius = 0f
        private var windowWidth = 0
        private var windowHeight = 0
        private var requestedStyle: AppVolumePanelStyle? = null
        private var panelStyle: AppVolumePanelStyle? = null
        private var styleWarningLogged = false
        private val motion = AppVolumeCardMotion(body)
        private var blurDrawable: Drawable? = null
        private var fallbackDrawable: GradientDrawable? = null
        private var blurManager: WindowManager? = null
        private var blurWarningLogged = false
        private val blurListener = Consumer<Boolean> { Reflect.attempt { applyBlur() } }
        val transition = AppVolumeCardTransition()

        fun prepare(style: AppVolumePanelStyle?) {
            requestedStyle = style
            panelStyle = null
            motion.cancel()
        }

        companion object {
            fun create(host: Any, root: ViewGroup): Card? {
                val pager = Reflect.readField(host, "p") as? View ?: return null
                val body = pager.parent as? LinearLayout ?: return null
                if (body.parent !== root || root !is LinearLayout) return null
                return Card(root, body, pager, host).also { card ->
                    body.addOnAttachStateChangeListener(card)
                    root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        val width = right - left
                        val height = bottom - top
                        if (width > 0 && height > 0 && (width != card.windowWidth || height != card.windowHeight)) {
                            card.windowWidth = width
                            card.windowHeight = height
                            Reflect.attempt { card.layout() }
                        }
                    }
                }
            }
        }

        fun setPages(views: List<ViewGroup>) {
            pages = views.map(::WeakReference)
            // Native tablet paging uses five columns; do not crop those pages to three.
            columns = views.maxOfOrNull { it.childCount }?.coerceAtLeast(1) ?: 1
            layout()
        }

        fun layout() {
            val host = controller.get() ?: return
            val context = root.context
            val metrics = context.resources.displayMetrics
            val real = NativeVolumePanelStyle.displayMetrics(root)
            @Suppress("DEPRECATION")
            val display = root.display ?: context.getSystemService(WindowManager::class.java).defaultDisplay
            fun AppVolumePanelStyle.current() = matches(real.widthPixels, real.heightPixels, real.densityDpi, display.rotation, display.displayId)
            val style = requestedStyle?.takeIf { it.current() } ?: panelStyle?.takeIf { it.current() }
                ?: NativeVolumePanelStyle.fallback(root)
            if (requestedStyle != null && requestedStyle?.current() != true) requestedStyle = null
            if (panelStyle != null && panelStyle != style) motion.cancel()
            panelStyle = style
            val availableWidth = windowWidth.takeIf { it > 0 } ?: metrics.widthPixels
            val availableHeight = windowHeight.takeIf { it > 0 } ?: metrics.heightPixels
            val origin = IntArray(2)
            if (root.isAttachedToWindow) root.getLocationOnScreen(origin)
            val targetTop = style?.localTop(origin[1], availableHeight, 0) ?: (availableHeight * .22f).toInt()
            val targetRight = style?.localRight(origin[0], availableWidth, 0) ?: (16 * metrics.density).toInt()
            val geometry = AppVolumeCardGeometry.calculate(
                (availableWidth - targetRight).coerceAtLeast(1),
                (availableHeight - targetTop).coerceAtLeast(1), metrics.density, columns, style)
            (root as LinearLayout).gravity = Gravity.RIGHT or Gravity.TOP
            root.setPadding(0, 0, 0, 0)
            root.clipChildren = false
            root.clipToPadding = false
            root.setBackgroundColor(Color.TRANSPARENT)
            body.gravity = Gravity.CENTER
            body.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.RIGHT or Gravity.TOP
                rightMargin = targetRight
                topMargin = targetTop
            }
            body.setPadding(geometry.horizontalPadding, geometry.padding, geometry.horizontalPadding, geometry.padding)
            body.isClickable = true
            body.isFocusable = true
            radius = style?.radius?.toFloat() ?: 28 * metrics.density
            body.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            body.clipToOutline = true
            body.elevation = 16 * metrics.density
            pager.layoutParams = (pager.layoutParams as LinearLayout.LayoutParams).apply {
                width = geometry.pagerWidth; height = geometry.sliderHeight
                setMargins(0, 0, 0, 0)
            }
            pager.minimumHeight = geometry.sliderHeight
            pages.mapNotNull { it.get() }.forEach { resizeSliders(it, geometry) }
            resizeSliders(pager, geometry)
            val indicator = Reflect.readField(host, "q") as? ViewGroup
            if (indicator != null) {
                val dots = (Reflect.readField(host, "r") as? List<*>)?.filterIsInstance<View>().orEmpty()
                indicator.visibility = if (dots.size > 1) View.VISIBLE else View.GONE
                (indicator.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.topMargin = (8 * metrics.density).toInt(); it.bottomMargin = 0
                    indicator.layoutParams = it
                }
                val selected = Reflect.callWith(pager, "getCurrentItem") as? Int ?: 0
                if (dots.size > 1 && indicator.childCount == 0) for (dot in dots) {
                    (dot.parent as? ViewGroup)?.removeView(dot)
                    indicator.addView(dot)
                }
                for (i in 0 until indicator.childCount) {
                    val dot = indicator.getChildAt(i)
                    val color = if (i == selected) Color.WHITE else 0x55FFFFFF
                    dot.isSelected = i == selected
                    (dot as? ImageView)?.setColorFilter(color)
                    dot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                }
            }
            if (body.isAttachedToWindow) applyBlur()
            body.measure(View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST))
            (body.layoutParams as LinearLayout.LayoutParams).let { lp ->
                lp.topMargin = style?.localTop(origin[1], availableHeight, body.measuredHeight)
                    ?: targetTop.coerceAtMost((availableHeight - body.measuredHeight).coerceAtLeast(0))
                lp.rightMargin = style?.localRight(origin[0], availableWidth, body.measuredWidth) ?: targetRight
                body.layoutParams = lp
            }
        }

        fun show() {
            if (requestedStyle == null && !styleWarningLogged) {
                styleWarningLogged = true
                ModuleLog.warn("app_volume no native snapshot: ${if (panelStyle == null) "bounded top fallback, no morph" else "resource-only geometry/base springs"}")
            }
            transition.reset()
            pager.clearAnimation()
            body.clearAnimation()
            applyBlur()
            motion.show { panelStyle }
        }

        fun hide(complete: (Int) -> Unit) {
            pager.clearAnimation()
            body.clearAnimation()
            val token = transition.close()
            motion.hide(panelStyle) { complete(token) }
        }

        override fun onViewAttachedToWindow(view: View) {
            transition.reset()
            Reflect.attempt {
                if (blurManager == null) {
                    val manager = body.context.getSystemService(WindowManager::class.java)
                    manager.addCrossWindowBlurEnabledListener(body.context.mainExecutor, blurListener)
                    blurManager = manager
                }
                layout()
                applyBlur()
            }
        }
        override fun onViewDetachedFromWindow(view: View) {
            transition.reset()
            windowWidth = 0; windowHeight = 0
            body.clearAnimation(); pager.clearAnimation()
            motion.cancel()
            requestedStyle = null; panelStyle = null
            blurManager?.let { Reflect.attempt { it.removeCrossWindowBlurEnabledListener(blurListener) } }
            blurManager = null
            body.background = null
            blurDrawable = null
            fallbackDrawable = null
        }

        private fun applyBlur() {
            if (!body.isAttachedToWindow) return
            val night = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val blur = runCatching {
                check(blurManager?.isCrossWindowBlurEnabled != false) { "System cross-window blur disabled" }
                val drawable = blurDrawable ?: run {
                    val viewRoot = View::class.java.getDeclaredMethod("getViewRootImpl").apply { isAccessible = true }.invoke(body)
                    viewRoot.javaClass.getMethod("createBackgroundBlurDrawable").apply { isAccessible = true }
                        .invoke(viewRoot) as Drawable
                }.also { blurDrawable = it }
                BlurDrawableApi.configure(drawable, 100f, radius, if (night) 0x801E1E22.toInt() else 0x77626262)
                drawable.alpha = 255
                drawable
            }.getOrElse {
                if (!blurWarningLogged) {
                    blurWarningLogged = true
                    ModuleLog.error("app_volume card blur unavailable; translucent fallback (not material parity)", it)
                }
                (fallbackDrawable ?: GradientDrawable().also { fallbackDrawable = it }).apply {
                    setColor(if (night) 0xD01E1E22.toInt() else 0xCC454548.toInt())
                    cornerRadius = radius
                }
            }
            if (body.background !== blur) body.background = blur
            body.setWillNotDraw(false)
        }
    }

    private fun resizeSliders(view: View, geometry: AppVolumeCardGeometry) {
        if (view is SeekBar) {
            view.layoutParams = view.layoutParams.apply {
                width = geometry.sliderWidth; height = geometry.sliderHeight
                if (this is ViewGroup.MarginLayoutParams) {
                    setMargins(geometry.halfGap, 0, geometry.halfGap, 0)
                    marginStart = geometry.halfGap; marginEnd = geometry.halfGap
                }
            }
            (view.parent as? View)?.let { column ->
                column.setPadding(0, 0, 0, 0)
                column.layoutParams = column.layoutParams.apply {
                    width = ViewGroup.LayoutParams.WRAP_CONTENT; height = ViewGroup.LayoutParams.WRAP_CONTENT
                    if (this is ViewGroup.MarginLayoutParams) { setMargins(0, 0, 0, 0); marginStart = 0; marginEnd = 0 }
                }
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) resizeSliders(view.getChildAt(i), geometry)
        }
    }

}
