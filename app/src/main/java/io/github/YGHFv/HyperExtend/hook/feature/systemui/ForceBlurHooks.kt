/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import io.github.YGHFv.HyperExtend.core.ForceBlurSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal class ForceBlurHooks private constructor(loader: ClassLoader, private val settings: HookSettings) {
    private val t = ForceBlurTargets
    private val methods = (t.callers + t.framework).associateWith {
        requireNotNull(NotificationHooks.resolve(loader, it))
    }
    private val fields = t.fields.associate { (owner, name, expected) ->
        name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
            require(type.name == expected && Modifier.isStatic(modifiers) == (name == "CROSS_WINDOW_BLUR_SUPPORTED"))
            isAccessible = true
        }
    }
    private val callerConstructor = Class.forName(t.CONSTRUCTOR_OWNER, false, loader)
        .getDeclaredConstructor(*t.constructor.map { Class.forName(it, false, loader) }.toTypedArray())
    private val scope = TileMaterialScope<Apply>()
    private val bindings = WeakHashMap<Any, WeakReference<Binding>>()
    private var version: Long? = null
    @Volatile private var ready = false
    private data class Apply(val owner: Any, val allowed: Boolean, val cleanup: Boolean, var relaxed: Boolean = false)
    private fun invoke(target: SystemUiMethod, owner: Any? = null, vararg args: Any?) =
        methods.getValue(target).invoke(owner, *args)
    private fun main() = Looper.myLooper() == Looper.getMainLooper()
    private fun host(): Boolean = runCatching {
        val context = invoke(t.application) as? Context ?: return@runCatching false
        if (context.packageName != "com.android.systemui") return@runCatching false
        val current = version ?: context.packageManager.getPackageInfo("com.android.systemui", 0)
            .compatibleVersionCode.also { version = it }
        current == 202602260L
    }.getOrDefault(false)
    private fun supported(owner: Any): Boolean = runCatching {
        if (!host()) return@runCatching false
        val listeners = fields.getValue("crossWindowBlurListeners").get(owner) ?: return@runCatching false
        ForceBlurPolicy.supported(settings.isOn(ForceBlurSettings.FEATURE), SafeModeRuntime.blocked, version,
            fields.getValue("CROSS_WINDOW_BLUR_SUPPORTED").getBoolean(null),
            invoke(t.property, null, "persist.sysui.disableBlur", false) != false,
            invoke(t.cross, listeners) == true)
    }.getOrDefault(false)

    private inner class Binding(owner: Any, root: Any, view: View, var radius: Int) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val ownerRef = WeakReference(owner)
        private val rootRef = WeakReference(root)
        private val viewRef = WeakReference(view)
        private var observer: ViewTreeObserver? = null
        private var stopped = false
        private val timeout = Runnable { stop(true) }
        fun sameRoot(root: Any) = rootRef.get() === root
        fun owns(): Boolean = runCatching {
            val owner = ownerRef.get() ?: return@runCatching false
            val root = rootRef.get() ?: return@runCatching false
            ForceBlurPolicy.owns(radius, fields.getValue("lastAppliedBlur").getInt(owner),
                fields.getValue("lastTargetViewRootImpl").get(owner) === root)
        }.getOrDefault(false)
        fun start() {
            val view = viewRef.get() ?: return
            view.addOnAttachStateChangeListener(this)
            view.postDelayed(timeout, 30_000)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }
        override fun onViewAttachedToWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        override fun onViewDetachedFromWindow(v: View) { stop(true) }
        override fun onPreDraw(): Boolean {
            if (!stopped) runCatching {
                val owner = ownerRef.get()
                val view = viewRef.get()
                if (owner == null || view == null || !owns() || SafeModeRuntime.blocked) stop(false)
                else if (!view.isShown || view.windowVisibility != View.VISIBLE || !supported(owner)) stop(true)
            }.onFailure { stop(false) }
            return true
        }
        fun stop(clear: Boolean) {
            if (stopped) return
            stopped = true
            val owner = ownerRef.get()
            val root = rootRef.get()
            val owned = owner != null && bindings[owner]?.get() === this && owns()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            viewRef.get()?.let { it.removeCallbacks(timeout); it.removeOnAttachStateChangeListener(this) }
            if (owner != null && bindings[owner]?.get() === this) bindings.remove(owner)
            if (clear && owned && root != null && owner != null && !SafeModeRuntime.blocked) runCatching {
                scope.within(Apply(owner, false, true)) { invoke(t.apply, owner, root, 0) }
            }
            ownerRef.clear(); rootRef.clear(); viewRef.clear()
        }
    }

    private fun install(): Int {
        if (!(t.callers.map { methods.getValue(it) } + callerConstructor)
                .all { HookRuntime.deoptimize(it, "${ForceBlurSettings.FEATURE}/caller") }) return 0
        val support = HookRuntime.hook(methods.getValue(t.support), "${ForceBlurSettings.FEATURE}/support") { chain ->
            val native = chain.proceed()
            if (!ready || !main()) return@hook native
            val owner = chain.thisObject ?: return@hook native
            val active = scope.current?.takeIf { it.owner === owner }
            if (active?.cleanup == true) return@hook true
            if (native == true || active?.allowed == false || !supported(owner)) return@hook native
            active?.relaxed = true
            true
        }
        val apply = HookRuntime.hook(methods.getValue(t.apply), "${ForceBlurSettings.FEATURE}/apply") { chain ->
            if (!ready || !main()) return@hook chain.proceed()
            val owner = chain.thisObject ?: return@hook chain.proceed()
            // Only our zero-radius replay enters this scope before applyBlur.
            if (scope.current?.let { it.owner === owner && it.cleanup } == true) {
                val args = chain.args.toTypedArray(); args[1] = 0
                return@hook chain.proceed(args)
            }
            val root = chain.args[0] ?: return@hook chain.proceed()
            val view = runCatching { invoke(t.rootView, root) as? View }.getOrNull()
            val allowed = view?.display?.displayId == 0 && host()
            var binding = bindings[owner]?.get()
            if (binding != null && (!binding.sameRoot(root) || !binding.owns())) {
                binding.stop(true); binding = null
            }
            val cleanup = binding != null && (!allowed || !supported(owner))
            val args = chain.args.toTypedArray()
            args[1] = ForceBlurPolicy.radius(args[1] as Int, cleanup)
            val active = Apply(owner, allowed, cleanup)
            try {
                scope.within(active) { chain.proceed(args) }
            } finally {
                runCatching {
                    val actual = fields.getValue("lastAppliedBlur").getInt(owner)
                    val same = fields.getValue("lastTargetViewRootImpl").get(owner) === root
                    if (active.relaxed && actual > 0 && same && view != null) {
                        val current = binding
                        if (current != null) current.radius = actual else {
                            val created = Binding(owner, root, view, actual)
                            bindings[owner] = WeakReference(created)
                            try { created.start() } catch (_: Throwable) { created.stop(true) }
                        }
                    } else binding?.stop(false)
                }
            }
        }
        ready = support && apply
        return if (ready) 2 else 0
    }
    companion object {
        fun install(loader: ClassLoader, settings: HookSettings): Int = ForceBlurHooks(loader, settings).install()
    }
}
