/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.HeaderWeatherSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(33)
internal object HeaderWeatherHooks {
    private data class Weather(val valid: Boolean, val temperature: Int, val unit: Int,
        val description: String?, val city: String?, val updated: Long)

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val api = Api(loader)
        val ready = AtomicBoolean(false)
        val bindings = WeakHashMap<ViewGroup, WeakReference<Binding>>()
        if (!(listOf(HeaderWeatherTargets.inflate, HeaderWeatherTargets.attach, HeaderWeatherTargets.capture) +
                HeaderWeatherTargets.captureCallers).all {
                HookRuntime.deoptimize(api.methods.getValue(it), "${HeaderWeatherSettings.FEATURE}/lifecycle")
            }) return 0
        val capture = HookRuntime.hookAfter(api.methods.getValue(api.t.capture), "${HeaderWeatherSettings.FEATURE}/userTracker") { chain, original ->
            if (ready.get() && Looper.myLooper() == Looper.getMainLooper()) runCatching {
                val tracker = api.fields.getValue("mUserContextProvider").get(chain.thisObject)
                if (api.trackerType.isInstance(tracker)) api.tracker = WeakReference(tracker)
            }
            original
        }
        val installed = listOf(HeaderWeatherTargets.inflate, HeaderWeatherTargets.attach).map { target ->
            HookRuntime.hookAfter(api.methods.getValue(target), "${HeaderWeatherSettings.FEATURE}/${target.name}") { chain, original ->
                val root = chain.thisObject as? ViewGroup
                if (ready.get() && root != null && Looper.myLooper() == Looper.getMainLooper()) runCatching {
                    val version = root.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode
                    if (version == 202602260L && root.context.hostDisplayIdOrNull() == 0 && bindings[root]?.get() == null) {
                        val binding = Binding(root, api, settings, version)
                        bindings[root] = WeakReference(binding)
                        root.addOnAttachStateChangeListener(binding)
                        if (root.isAttachedToWindow) binding.onViewAttachedToWindow(root)
                    }
                }.onFailure { ModuleLog.warn("header_weather: binding unavailable; native header retained") }
                original
            }
        }
        ready.set(capture && installed.all { it })
        return if (ready.get()) installed.size + 1 else 0
    }

    private class Api(loader: ClassLoader) {
        val t = HeaderWeatherTargets
        val methods = t.methods.associateWith { checkNotNull(NotificationHooks.resolve(loader, it)) }
        val fields = t.fields.associate { (owner, name, expected) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        val constructor = Class.forName(t.FETCHER, false, loader).getDeclaredConstructor()
        val callbackType = Class.forName(t.CALLBACK, false, loader)
        val trackerType = Class.forName(t.USER_TRACKER, false, loader)
        var tracker = WeakReference<Any>(null)
        fun user(): Int? = runCatching { tracker.get()?.let { methods.getValue(t.user).invoke(it) as? Int } }.getOrNull()
        fun queryContext(context: Context, user: Int): Context? = runCatching {
            val owner = tracker.get() ?: return@runCatching null
            if (methods.getValue(t.user).invoke(owner) != user) return@runCatching null
            val result = methods.getValue(t.userContext).invoke(owner, context) as? Context
            result.takeIf { methods.getValue(t.user).invoke(owner) == user }
        }.getOrNull()
        fun weather(bean: Any): Weather = Weather(
            methods.getValue(t.valid).invoke(bean) == true,
            methods.getValue(t.temperature).invoke(bean) as Int, fields.getValue("temperatureUnit").getInt(bean),
            methods.getValue(t.description).invoke(bean) as? String, methods.getValue(t.city).invoke(bean) as? String,
            methods.getValue(t.time).invoke(bean) as Long)
    }

    private class Binding(val root: ViewGroup, val api: Api, val settings: HookSettings, val version: Long) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private var observer: ViewTreeObserver? = null
        private var label: TextView? = null
        private var fetcher: Any? = null
        private val refresh = HeaderWeatherRefresh<Weather>()
        private var failed = false
        private fun allowed() = !failed && !SafeModeRuntime.blocked && HeaderWeatherPolicy.eligible(
            settings.isOn(HeaderWeatherSettings.FEATURE), version,
            root.display?.displayId ?: root.context.hostDisplayIdOrNull(), refresh.user,
            api.tracker.get() != null)

        override fun onViewAttachedToWindow(v: View) = protect {
            if (observer != null) return@protect
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            cancel(clear = false)
        }

        override fun onPreDraw(): Boolean {
            protect {
                if (failed || SafeModeRuntime.blocked || !settings.isOn(HeaderWeatherSettings.FEATURE)) {
                    cancel(clear = true)
                    return@protect
                }
                val now = SystemClock.elapsedRealtime()
                // Native synchronized property, not a per-frame Binder/ActivityManager call.
                val user = api.user()
                if (user != refresh.user) cancel(clear = true)
                refresh.selectUser(user)
                if (!allowed()) {
                    cancel(clear = true)
                    return@protect
                }
                if (!visible()) {
                    cancel(clear = false)
                    return@protect
                }
                val anchor = listOf("mDateView", "mLandClock").mapNotNull {
                    api.fields.getValue(it).get(root) as? TextView
                }.firstOrNull { it.isShown && it.alpha > 0.01f && it.width > 0 }
                if (anchor == null) { cancel(clear = false); return@protect }
                refresh.begin(now)?.let { request(it) }
                render(anchor)
            }
            return true
        }

        private fun protect(block: () -> Unit) {
            runCatching(block).onFailure {
                if (!failed) ModuleLog.warn("header_weather: adapter failed; remove only owned label")
                failed = true
                cancel(clear = true)
            }
        }

        private fun visible() = root.isAttachedToWindow && root.windowVisibility == View.VISIBLE &&
            root.isShown && root.alpha > 0.01f

        private fun request(token: Long) {
            val user = refresh.user ?: return
            val context = api.queryContext(root.context, user) ?: run {
                refresh.suspendRequest()
                return
            }
            val weak = WeakReference(this)
            val callbackMethod = api.methods.getValue(api.t.callback)
            val callback = Proxy.newProxyInstance(api.callbackType.classLoader, arrayOf(api.callbackType)) { proxy, method, args ->
                when {
                    method.declaringClass == Any::class.java && method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.declaringClass == Any::class.java && method.name == "equals" -> proxy === args?.getOrNull(0)
                    method.declaringClass == Any::class.java && method.name == "toString" -> "HeaderWeatherCallback"
                    method == callbackMethod -> {
                        weak.get()?.receive(token, user, args?.getOrNull(0))
                        null
                    }
                    else -> null
                }
            }
            val instance = fetcher ?: api.constructor.newInstance().also { fetcher = it }
            // Native async provider query, cursor closing, timeout and request ordering.
            // No location/network request, provider write or module executor is added.
            api.methods.getValue(api.t.fetch).invoke(instance, context, false, callback)
        }

        private fun receive(token: Long, user: Int, bean: Any?) {
            if (Looper.myLooper() != Looper.getMainLooper()) return
            protect {
                if (!refresh.accepts(token, user)) return@protect
                if (!allowed() || api.user() != user) {
                    cancel(clear = true)
                    return@protect
                }
                if (!visible()) {
                    cancel(clear = false)
                    return@protect
                }
                if (refresh.complete(token, user, bean?.let(api::weather))) root.invalidate()
            }
        }

        private fun hide() {
            label?.let { if (it.parent === root) root.removeView(it) }
        }

        private fun cancel(clear: Boolean) {
            if (clear) refresh.clear() else refresh.suspendRequest()
            if (fetcher != null) {
                runCatching { api.methods.getValue(api.t.reset).invoke(fetcher) }
                fetcher = null
            }
            hide()
        }

        private fun bounds(view: View): Rect = Rect().also {
            view.getDrawingRect(it)
            root.offsetDescendantRectToMyCoords(view, it)
        }

        private fun render(anchor: TextView) {
            val data = refresh.value ?: return hide()
            val text = HeaderWeatherPolicy.text(data.valid, data.temperature, data.unit, data.description, data.city,
                settings.isOn(HeaderWeatherSettings.CITY), data.updated, System.currentTimeMillis()) ?: return hide()
            if (anchor.scaleX != 1f || anchor.scaleY != 1f || anchor.rotation != 0f) return hide()
            val view = label ?: TextView(root.context).apply {
                id = View.generateViewId()
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }.also { label = it }
            if (view.text.toString() != text) view.text = text
            if (view.contentDescription?.toString() != text) view.contentDescription = text
            if (view.typeface != anchor.typeface) view.typeface = anchor.typeface
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            if (view.currentTextColor != anchor.currentTextColor) view.setTextColor(anchor.currentTextColor)
            if (view.letterSpacing != anchor.letterSpacing) view.letterSpacing = anchor.letterSpacing
            view.alpha = anchor.alpha
            val rect = bounds(anchor)
            val obstacles = ArrayList<HeaderWeatherPolicy.Space>()
            fun scan(parent: ViewGroup) {
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i)
                    if (child === anchor || child === view || !child.isShown || child.alpha <= 0.01f) continue
                    if (child is ViewGroup) scan(child) else {
                        val area = bounds(child)
                        if (area.bottom > rect.top && area.top < rect.bottom)
                            obstacles += HeaderWeatherPolicy.Space(area.left, area.right)
                    }
                }
            }
            scan(root)
            val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val gap = (root.resources.displayMetrics.density * 8).toInt()
            val space = HeaderWeatherPolicy.space(root.width, rect.left, rect.right, rtl, gap, obstacles) ?: return hide()
            if (space.right - space.left < gap * 4) return hide()
            view.measure(View.MeasureSpec.makeMeasureSpec(space.right - space.left, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
            val x = if (rtl) space.right - view.measuredWidth else space.left
            val y = rect.top + anchor.baseline - view.baseline
            if (y < 0 || y + view.measuredHeight > root.height) return hide()
            if (view.parent == null) root.addView(view, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }
}
