/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.YGHFv.HyperExtend.core.LockscreenMaterialSettings as Config
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class LockscreenMaterialHooks private constructor(loader: ClassLoader, hostLoader: ClassLoader,
    private val settings: HookSettings) {
    private val backdrop = OwnedBackdrop(hostLoader)
    private val manager = Class.forName(LockscreenMaterialTargets.MANAGER, false, loader)
        .getDeclaredField("INSTANCE").apply { require(type.name == LockscreenMaterialTargets.MANAGER); isAccessible = true }
    private val queries = LockscreenMaterialTargets.queries
        .associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
    private val advanced = settings.isOn(Config.ADVANCED)
    private val radius = Config.radius(settings.string(Config.RADIUS))
    private val color = Config.color(settings.string(Config.COLOR))
    private val bindings = WeakHashMap<ImageView, WeakReference<Binding>>()
    @Volatile private var ready = false
    private var supported: Boolean? = null

    private fun ordinary(): Boolean {
        val current = manager.get(null) ?: return false
        return queries.getValue(LockscreenFlashlightTargets.interactive).invoke(current) == true &&
            queries.getValue(LockscreenFlashlightTargets.showing).invoke(current) == true &&
            queries.getValue(LockscreenFlashlightTargets.occluded).invoke(current) == false &&
            queries.getValue(LockscreenFlashlightTargets.goingAway).invoke(current) == false
    }

    private inner class Binding(owner: Any, val image: ImageView, val layout: FrameLayout) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        val owner = WeakReference(owner)
        private var background: View? = null
        private var observer: ViewTreeObserver? = null
        private var originalTint: ColorStateList? = null
        private var ownedTint: ColorStateList? = null
        private var diameter = 0
        private var capability = false
        private var nextCapability = 0L
        private var failed = false
        fun start() {
            image.addOnAttachStateChangeListener(this)
            if (image.isAttachedToWindow) onViewAttachedToWindow(image)
        }
        fun restore() {
            background?.let { backdrop.clear(it, advanced); if (it.parent === layout) layout.removeView(it) }
            background = null; diameter = 0
            if (ownedTint != null && image.imageTintList === ownedTint) image.imageTintList = originalTint
            ownedTint = null; originalTint = null
        }
        fun dispose() {
            restore()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            image.removeOnAttachStateChangeListener(this)
            if (bindings[image]?.get() === this) bindings.remove(image)
            owner.clear()
        }
        fun update() {
            if (failed || !ready || SafeModeRuntime.blocked || !settings.isOn(Config.FEATURE) ||
                owner.get() == null || image.parent !== layout || !image.isAttachedToWindow ||
                image.display?.displayId != 0 || !image.isShown || image.alpha <= 0.01f ||
                image.drawable == null || !ordinary()) { restore(); return }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextCapability) {
                capability = backdrop.allowed(image.context); nextCapability = now + 1000
            }
            if (!capability) { restore(); return }
            val size = LockscreenMaterialPolicy.diameter(radius, image.resources.displayMetrics.density,
                minOf(image.width, layout.width), minOf(image.height, layout.height)) ?: run { restore(); return }
            if (size != diameter) restore()
            val view = background ?: View(image.context).also {
                it.isClickable = false; it.isFocusable = false
                it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                it.clipToOutline = true
                it.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.setOval(0, 0, view.width, view.height) }
                }
                layout.addView(it, layout.indexOfChild(image), FrameLayout.LayoutParams(size, size))
                background = it
                if (!backdrop.attach(it, advanced, size / 2f, color)) { restore(); return }
                diameter = size
            }
            view.x = image.x + (image.width - size) / 2f
            view.y = image.y + (image.height - size) / 2f
            view.alpha = image.alpha; view.scaleX = image.scaleX; view.scaleY = image.scaleY
            LockscreenMaterialPolicy.foreground(color)?.let { tint ->
                if (ownedTint == null || image.imageTintList !== ownedTint) {
                    originalTint = image.imageTintList
                    ownedTint = ColorStateList.valueOf(tint)
                    image.imageTintList = ownedTint
                }
            }
        }
        override fun onViewAttachedToWindow(v: View) {
            if (observer == null) observer = image.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        override fun onViewDetachedFromWindow(v: View) {
            restore()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
        }
        override fun onPreDraw(): Boolean {
            runCatching { update() }.onFailure { failed = true; runCatching { restore() } }
            return true
        }
    }

    private fun install(loader: ClassLoader): Int {
        val update = requireNotNull(NotificationHooks.resolve(loader, LockscreenShortcutTargets.update))
        val release = requireNotNull(NotificationHooks.resolve(loader, LockscreenShortcutTargets.release))
        val data = requireNotNull(NotificationHooks.resolve(loader, LockscreenMaterialTargets.caller))
        val releaseCallers = LockscreenMaterialTargets.releaseCallers.map { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val constructor = update.declaringClass.getDeclaredConstructor(android.content.Context::class.java,
            android.content.Context::class.java, android.view.ViewGroup::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            Class.forName(LockscreenShortcutTargets.VIEW + "\$ShortcutTipsAnimCallback", false, loader))
        if (!(listOf(data, constructor) + releaseCallers).all { HookRuntime.deoptimize(it, "${Config.FEATURE}/caller") }) return 0
        val first = HookRuntime.hookAfter(update, "${Config.FEATURE}/bind") { chain, result ->
            if (!ready || Looper.myLooper() != Looper.getMainLooper()) return@hookAfter result
            val image = chain.args[1] as? ImageView ?: return@hookAfter result
            val layout = chain.args[2] as? FrameLayout ?: return@hookAfter result
            val host = supported ?: runCatching {
                val pm = image.context.packageManager
                LockscreenShortcutPolicy.supported(pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                    pm.getPackageInfo(LockscreenShortcutPolicy.PACKAGE, 0).compatibleVersionCode)
            }.getOrDefault(false).also { supported = it }
            bindings[image]?.get()?.takeIf { it.owner.get() !== chain.thisObject || it.layout !== layout }?.dispose()
            if (host && image.parent === layout && bindings[image]?.get() == null) {
                val binding = Binding(chain.thisObject!!, image, layout)
                bindings[image] = WeakReference(binding); binding.start()
            }
            result
        }
        val second = HookRuntime.hook(release, "${Config.FEATURE}/release") { chain ->
            if (Looper.myLooper() == Looper.getMainLooper())
                bindings.values.mapNotNull { it.get() }.filter { it.owner.get() === chain.thisObject }.forEach { it.dispose() }
            chain.proceed()
        }
        ready = first && second
        return if (ready) 2 else 0
    }
    companion object {
        fun install(loader: ClassLoader, hostLoader: ClassLoader, settings: HookSettings) =
            LockscreenMaterialHooks(loader, hostLoader, settings).install(loader)
    }
}
