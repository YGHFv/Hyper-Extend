/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal class ToastMaterialHooks private constructor(loader: ClassLoader, private val settings: HookSettings) {
    private val t = ToastMaterialTargets
    private val backdrop = OwnedBackdrop(loader)
    private val nativeBlur = requireNotNull(NotificationHooks.resolve(loader, t.nativeBlur))
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    @Volatile private var ready = false

    private inner class Binding(view: View, text: TextView) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val viewRef = WeakReference(view)
        private val textRef = WeakReference(text)
        private val power = view.context.getSystemService(PowerManager::class.java)
        private val keyguard = view.context.getSystemService(KeyguardManager::class.java)
        private var native: Drawable? = null
        private var owned: Drawable? = null
        private var observer: ViewTreeObserver? = null
        private var stopped = false
        private val started = SystemClock.elapsedRealtime()
        private var nextCheck = 0L
        private val timeout = Runnable { stop() }
        fun start() {
            val view = viewRef.get() ?: return
            view.addOnAttachStateChangeListener(this)
            view.postDelayed(timeout, 30_000)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }
        override fun onViewAttachedToWindow(v: View) {
            if (stopped) return
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        override fun onViewDetachedFromWindow(v: View) { stop() }
        private fun update() {
            val view = viewRef.get() ?: run { stop(); return }
            val text = textRef.get() ?: run { stop(); return }
            val now = SystemClock.elapsedRealtime()
            if (!ready || !settings.isOn(t.FEATURE) || SafeModeRuntime.blocked ||
                !view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
                owned != null && view.background !== owned) { stop(); return }
            if (now < nextCheck) return
            nextCheck = now + 500
            if (!ToastMaterialPolicy.eligible(true, false, true, true, view.display?.displayId == 0,
                    power?.isInteractive == true, keyguard?.isKeyguardLocked != false,
                    nativeBlur.invoke(null, view) == true, now, started) || !backdrop.allowed(view.context)) {
                stop(); return
            }
            if (owned != null) return
            val radius = ToastMaterialPolicy.radius(view.width, view.height, view.resources.displayMetrics.density) ?: return
            val original = view.background ?: return
            if (backdrop.isBlur(original)) { stop(); return }
            val material = backdrop.drawable(view, radius, ToastMaterialPolicy.tint(text.currentTextColor)) ?: run { stop(); return }
            native = original; owned = material
            // Preserve text, icon, native measurement, animation, outline and all Toast
            // presenter/window/accessibility handling; only this background is owned.
            val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            view.background = material
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
        override fun onPreDraw(): Boolean {
            if (!stopped) runCatching { update() }.onFailure { runCatching { stop() } }
            return true
        }
        fun stop() {
            if (stopped) return
            stopped = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            val view = viewRef.get()
            if (view != null) {
                view.removeOnAttachStateChangeListener(this); view.removeCallbacks(timeout)
                if (owned != null && view.background === owned) {
                    val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
                    view.background = native
                    view.setPadding(padding[0], padding[1], padding[2], padding[3])
                }
                if (bindings[view]?.get() === this) bindings.remove(view)
            }
            owned?.let { backdrop.release(it) }
            owned = null; native = null; viewRef.clear(); textRef.clear()
        }
    }
    private fun install(loader: ClassLoader): Int {
        fun type(name: String): Class<*> = when (name) {
            "int" -> Int::class.javaPrimitiveType!!
            else -> Class.forName(name, false, loader)
        }
        val constructor = type(t.TOAST).getDeclaredConstructor(*t.constructor.map(::type).toTypedArray())
        val viewField = type(t.TOAST).getDeclaredField("mToastView").apply {
            require(type == View::class.java && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val caller = NotificationHooks.resolve(loader, t.create) ?: return 0
        val dispatch = t.callers.map { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!(listOf(constructor, caller) + dispatch).all { HookRuntime.deoptimize(it, "${t.FEATURE}/create") }) return 0
        ready = HookRuntime.hookAfter(constructor, "${t.FEATURE}/bind") { chain, original ->
            if (!ready || !settings.isOn(t.FEATURE) || chain.args[4] != null ||
                Looper.myLooper() != Looper.getMainLooper()) return@hookAfter original
            val view = viewField.get(chain.thisObject) as? View ?: return@hookAfter original
            // Toast 的构造器、视图字段、资源名和调用点已在安装阶段按宿主结构解析。
            if (view.javaClass.name != t.VIEW ||
                view.resources.getResourceName(t.TEXT_ID) != "com.android.systemui:id/text") return@hookAfter original
            val text = view.findViewById<TextView>(t.TEXT_ID) ?: return@hookAfter original
            bindings[view]?.get()?.stop()
            val binding = Binding(view, text)
            bindings[view] = WeakReference(binding)
            try { binding.start() } catch (_: Throwable) { binding.stop() }
            original
        }
        return if (ready) 1 else 0
    }
    companion object {
        fun install(loader: ClassLoader, settings: HookSettings): Int = ToastMaterialHooks(loader, settings).install(loader)
    }
}
