/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.WIFI_STANDARD_KEY
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object WifiIndicatorHooks {
    private const val FEATURE = "status_bar_icons"
    private const val ACTIVITY = "$FEATURE.wifi_activity"

    // 宿主 id 来自该版本 bind 方法，模块本地 R 表中不存在这些条目。
    @SuppressLint("ResourceType")
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(ACTIVITY) && settings.number(WIFI_STANDARD_KEY, 0) != 2) return 0
        val t = WifiIndicatorTargets
        val methods = (listOf(t.bind) + t.render + t.callers).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$FEATURE/wifi-caller") }) return 0
        val ready = AtomicBoolean(false)
        val host = StatusIconHost()
        val hidden = WeakHashMap<View, Hidden>()
        fun enabled(activity: Boolean): Boolean {
            if (SafeModeRuntime.blocked) return false
            val now = HookRuntime.settings()
            return now.isOn(FEATURE) &&
                if (activity) now.isOn(ACTIVITY) else now.number(WIFI_STANDARD_KEY, 0) == 2
        }
        fun apply(view: View?, activity: Boolean) {
            if (view == null || !ready.get() || !host.eligible(view)) return
            // The original bind/render just supplied a fresh native visibility.
            hidden.remove(view)?.release(restore = false)
            if (enabled(activity)) {
                Hidden(view) { target -> enabled(activity) && host.eligible(target) }.also {
                    hidden[view] = it
                    it.start()
                }
            }
        }
        val hooks = listOf(
            HookRuntime.hook(methods.getValue(t.bind), "$FEATURE/wifi-bind") { chain ->
                val view = chain.args[0] as? ViewGroup
                val allowed = view != null && ready.get() && host.eligible(view)
                if (allowed) {
                    listOf(0x7f0b0dac, 0x7f0b0dc2).forEach { id ->
                        view?.findViewById<View>(id)?.let { hidden.remove(it)?.release(true) }
                    }
                }
                val result = chain.proceed()
                if (view != null && allowed) {
                    // IDs and child types are read from this exact version's bind method.
                    apply(view.findViewById<ImageView>(0x7f0b0dac), true)
                    apply(view.findViewById<TextView>(0x7f0b0dc2), false)
                }
                result
            },
            *t.render.mapIndexed { index, target ->
                HookRuntime.hookAfter(methods.getValue(target), "$FEATURE/wifi-render-$index") { chain, result ->
                    apply(fields[index].get(chain.thisObject) as? View, index == 0)
                    result
                }
            }.toTypedArray(),
        )
        ready.set(hooks.all { it })
        return if (ready.get()) hooks.size else 0
    }

    // No View tags, Flow replacement, jobs, or strong map-to-View reference.
    private class Hidden(view: View, val enabled: (View) -> Boolean) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val viewRef = WeakReference(view)
        private val native = view.visibility
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var active = true

        fun start() {
            val target = viewRef.get() ?: return
            target.visibility = View.GONE
            target.addOnAttachStateChangeListener(this)
            if (target.isAttachedToWindow) onViewAttachedToWindow(target)
        }
        override fun onViewAttachedToWindow(v: View) {
            if (!active) return
            observer.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = WeakReference(v.viewTreeObserver)
            v.viewTreeObserver.addOnPreDrawListener(this)
        }
        override fun onViewDetachedFromWindow(v: View) = release(true)
        override fun onPreDraw(): Boolean {
            val target = viewRef.get()
            if (target == null || target.visibility != View.GONE) release(false)
            else if (!runCatching { enabled(target) }.getOrDefault(false)) release(true)
            return true
        }
        fun release(restore: Boolean) {
            if (!active) return
            active = false
            observer.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer.clear()
            viewRef.get()?.let {
                it.removeOnAttachStateChangeListener(this)
                if (restore) StatusIconPolicy.restoreVisibility(it.visibility, native, true)?.let { value ->
                    it.visibility = value
                }
            }
        }
    }
}
