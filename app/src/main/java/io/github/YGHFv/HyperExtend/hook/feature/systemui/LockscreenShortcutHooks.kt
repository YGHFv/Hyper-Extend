/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * Adaptation of CustomizeBottomButton / RemoveCamera to the OS4 shortcut plugin.
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.annotation.TargetApi
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap

@TargetApi(28)
internal class LockscreenShortcutHooks private constructor(loader: ClassLoader, private val hostLoader: ClassLoader, settings: HookSettings) {
    private val hideLeft = settings.isOn(LockscreenShortcutPolicy.LEFT)
    private val hideRight = settings.isOn(LockscreenShortcutPolicy.RIGHT)
    private val replaceLeft = LockscreenShortcutPolicy.replaceLeft(settings.isOn(LockscreenShortcutPolicy.FLASHLIGHT), hideLeft)
    private val flashlight = if (replaceLeft) runCatching { LockscreenFlashlightBridge(hostLoader) }
        .onFailure { ModuleLog.error("lockscreen flashlight controller unsupported; retaining native left entry", it) }.getOrNull() else null
    private val type = requireNotNull(Reflect.loadClass(loader, LockscreenShortcutTargets.VIEW))
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val left = field("shortcutViewLeft")
    private val right = field("shortcutViewRight")
    private val leftLayout = field("shortcutViewLeftLayout")
    private val rightLayout = field("shortcutViewRightLayout")
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private val flashBindings = WeakHashMap<View, WeakReference<LockscreenFlashlightBinding>>()
    private class Gesture(val binding: WeakReference<LockscreenFlashlightBinding>, val generation: Long, val slop: Float) { val press = FlashlightPress() }
    private val gestures = WeakHashMap<Any, Gesture>()
    private var flashReady = false
    private var ordinaryLockscreen: () -> Boolean = { false }
    private var talkbackEnabled: () -> Boolean = { false }
    @Volatile private var ready = false
    @Volatile private var supported: Boolean? = null

    private fun supported(context: Context): Boolean = supported ?: runCatching {
        val pm = context.packageManager
        LockscreenShortcutPolicy.supported(pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
            pm.getPackageInfo(LockscreenShortcutPolicy.PACKAGE, 0).compatibleVersionCode)
    }.getOrDefault(false).also { supported = it }

    private inner class Binding(owner: Any, val image: ImageView, val layout: View, val isLeft: Boolean) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val owner = WeakReference(owner)
        private val imageState = ShortcutVisibilityState()
        private val layoutState = ShortcutVisibilityState()
        private var observer: ViewTreeObserver? = null
        private var disposed = false
        fun owns(): Boolean = !disposed && owner.get()?.let {
            (if (isLeft) left else right).get(it) === image &&
                (if (isLeft) leftLayout else rightLayout).get(it) === layout && image.parent === layout
        } == true

        fun start() {
            image.addOnAttachStateChangeListener(this)
            enforce()
            if (image.isAttachedToWindow) onViewAttachedToWindow(image)
        }

        private fun enforce() {
            if (!ready || SafeModeRuntime.blocked || !owns()) { dispose(); return }
            image.visibility = imageState.hide(image.visibility)
            layout.visibility = layoutState.hide(layout.visibility)
        }

        fun dispose() {
            if (disposed) return
            disposed = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            image.removeOnAttachStateChangeListener(this)
            image.visibility = imageState.restore(image.visibility)
            layout.visibility = layoutState.restore(layout.visibility)
            if (bindings[image]?.get() === this) bindings.remove(image)
            owner.clear()
        }

        private fun guarded(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                runCatching { dispose() }
                ModuleLog.error("lockscreen shortcuts: binding failed", failure)
                SafeModeRuntime.trip("Hook failed: lockscreen shortcuts")
            }
        }

        override fun onViewAttachedToWindow(v: View) = guarded {
            if (!disposed) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                observer = image.viewTreeObserver.also { it.addOnPreDrawListener(this) }
                enforce()
            }
        }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            // Keep the attachment listener for native detach/reattach without a data rebind.
            image.visibility = imageState.restore(image.visibility)
            layout.visibility = layoutState.restore(layout.visibility)
        }
        override fun onPreDraw(): Boolean { guarded { enforce() }; return true }
    }

    private fun install(loader: ClassLoader): Int {
        val specs = LockscreenShortcutTargets.hooks + LockscreenShortcutTargets.queries + LockscreenShortcutTargets.callers
        val methods = specs.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        if (!LockscreenShortcutTargets.callers.map { HookRuntime.deoptimize(methods.getValue(it), "lockscreen_shortcuts/${it.name}") }.all { it }) return 0
        val suffix = Integer.toHexString(System.identityHashCode(loader))
        var flashHooks = 0
        if (flashlight != null) runCatching {
            val touch = requireNotNull(NotificationHooks.resolve(loader, LockscreenFlashlightTargets.touch))
            val intercept = requireNotNull(NotificationHooks.resolve(loader, LockscreenFlashlightTargets.intercept))
            val queries = LockscreenFlashlightTargets.pluginQueries.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
            val manager = Class.forName("com.miui.keyguard.shortcuts.manager.MiuiShortcutManager", false, loader).getDeclaredField("INSTANCE").get(null)
            ordinaryLockscreen = {
                queries.getValue(LockscreenFlashlightTargets.interactive).invoke(manager) == true &&
                    queries.getValue(LockscreenFlashlightTargets.showing).invoke(manager) == true &&
                    queries.getValue(LockscreenFlashlightTargets.occluded).invoke(manager) == false &&
                    queries.getValue(LockscreenFlashlightTargets.goingAway).invoke(manager) == false
            }
            talkbackEnabled = { queries.getValue(LockscreenFlashlightTargets.talkback).invoke(manager) == true }
            val moveType = touch.declaringClass
            fun moveField(name: String) = moveType.getDeclaredField(name).apply { isAccessible = true }
            val icon = moveField("leftIcon")
            val leftTouched = moveField("isLeftShortcutTouched")
            val rightTouched = moveField("isRightShortcutTouched")
            val callers = LockscreenFlashlightTargets.callers.map { requireNotNull(NotificationHooks.resolve(loader, it)) } +
                LockscreenFlashlightTargets.hostCallers.map { requireNotNull(NotificationHooks.resolve(hostLoader, it)) }
            if (callers.map { HookRuntime.deoptimize(it, "lockscreen_flashlight/${it.name}") }.all { it }) {
                flashReady = HookRuntime.hook(touch, "lockscreen_shortcuts/flashlightTouch/$suffix") { chain ->
                    if (!ready || !flashReady || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                    val owner = chain.thisObject!!
                    val event = chain.args[0] as MotionEvent
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        gestures.remove(owner)
                        val binding = flashBindings[icon.get(owner) as? View]?.get()
                        if (binding?.usable() == true && event.pointerCount == 1 &&
                            !leftTouched.getBoolean(owner) && !rightTouched.getBoolean(owner) && intercept.invoke(owner) == false &&
                            methods.getValue(LockscreenShortcutTargets.hit).invoke(owner, binding.image, event.x, event.y) == true) {
                            val gesture = Gesture(WeakReference(binding), binding.generation, ViewConfiguration.get(binding.image.context).scaledTouchSlop.toFloat())
                            gesture.press.down(event.eventTime, event.x, event.y, event.getPointerId(0))
                            gestures[owner] = gesture
                            return@hook true
                        }
                    }
                    val gesture = gestures[owner] ?: return@hook chain.proceed()
                    val binding = gesture.binding.get()
                    if (binding?.usable() != true || binding.generation != gesture.generation) gesture.press.cancel()
                    for (index in 0 until event.historySize) {
                        gesture.press.move(event.getHistoricalX(index), event.getHistoricalY(index), event.getPointerId(0), event.pointerCount, gesture.slop)
                    }
                    gesture.press.move(event.x, event.y, event.getPointerId(0), event.pointerCount, gesture.slop)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> gesture.press.cancel()
                        MotionEvent.ACTION_CANCEL -> { gesture.press.reset(); gestures.remove(owner) }
                        MotionEvent.ACTION_UP -> {
                            gestures.remove(owner)
                            if (gesture.press.up(event.eventTime)) binding?.toggle()
                        }
                    }
                    // This stream never entered native onTouchDown; native swipe cleanup is untouched.
                    // The outer KeyguardMoveHelper stores this result as mIsShortcutMoving.
                    // Release that flag on terminal events so the host finishes its own stream.
                    FlashlightPress.continues(event.actionMasked)
                }
                if (flashReady) flashHooks = 1
            }
        }.onFailure { ModuleLog.error("lockscreen flashlight gesture unsupported; retaining native left entry", it) }
        val installed = mutableListOf<Boolean>()
        installed += HookRuntime.hook(methods.getValue(LockscreenShortcutTargets.update), "lockscreen_shortcuts/update/$suffix") { chain ->
            if (!ready || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
            val image = chain.args[1] as? ImageView ?: return@hook chain.proceed()
            // Restore before the native bind, never restore the old baseline over fresh host data.
            bindings[image]?.get()?.dispose()
            flashBindings[image]?.get()?.dispose()
            val result = chain.proceed()
            val owner = chain.thisObject!!
            val layout = chain.args[2] as? View ?: return@hook result
            val side = when (image) { left.get(owner) -> true; right.get(owner) -> false; else -> null }
            if (supported(image.context) && LockscreenShortcutPolicy.hide(side, hideLeft, hideRight) &&
                (if (side == true) leftLayout else rightLayout).get(owner) === layout && image.parent === layout) {
                val binding = Binding(owner, image, layout, side!!)
                bindings[image] = WeakReference(binding)
                try { binding.start() } catch (failure: Throwable) { binding.dispose(); throw failure }
            } else if (flashReady && side == true && supported(image.context) && leftLayout.get(owner) === layout && image.parent === layout) {
                val controller = flashlight?.controller()
                if (controller != null) {
                    val weakOwner = WeakReference(owner)
                    val binding = LockscreenFlashlightBinding(image, layout, flashlight, controller,
                        owns = { ready && weakOwner.get()?.let { left.get(it) === image && leftLayout.get(it) === layout && image.parent === layout } == true },
                        ordinaryLockscreen = ordinaryLockscreen,
                        removed = { flashBindings.remove(image) })
                    flashBindings[image] = WeakReference(binding)
                    try { binding.start() } catch (failure: Throwable) { binding.dispose(); throw failure }
                }
            }
            result
        }
        installed += HookRuntime.hook(methods.getValue(LockscreenShortcutTargets.hit), "lockscreen_shortcuts/hit/$suffix") { chain ->
            if (ready && Looper.myLooper() == Looper.getMainLooper() &&
                bindings[chain.args[0] as? View]?.get()?.owns() == true) false else chain.proceed()
        }
        installed += HookRuntime.hook(methods.getValue(LockscreenShortcutTargets.click), "lockscreen_shortcuts/click/$suffix") { chain ->
            if (ready && supported == true && LockscreenShortcutPolicy.hide(chain.args[0] as? Boolean, hideLeft, hideRight)) null
            else {
                val binding = if (ready && flashReady && chain.args[0] == true && Looper.myLooper() == Looper.getMainLooper())
                    flashBindings[left.get(chain.thisObject) as? View]?.get() else null
                if (binding?.usable() == true) {
                    if (talkbackEnabled()) binding.toggle()
                    null
                } else chain.proceed()
            }
        }
        installed += HookRuntime.hook(methods.getValue(LockscreenShortcutTargets.launch), "lockscreen_shortcuts/launch/$suffix") { chain ->
            if (ready && supported == true && LockscreenShortcutPolicy.blockLaunch(
                    methods.getValue(LockscreenShortcutTargets.selectedLeft).invoke(chain.thisObject) == true,
                    methods.getValue(LockscreenShortcutTargets.selectedRight).invoke(chain.thisObject) == true, hideLeft, hideRight)) false
            else chain.proceed()
        }
        installed += HookRuntime.hook(methods.getValue(LockscreenShortcutTargets.release), "lockscreen_shortcuts/release/$suffix") { chain ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listOf(left, right).forEach { bindings[it.get(chain.thisObject) as? View]?.get()?.dispose() }
                flashBindings[left.get(chain.thisObject) as? View]?.get()?.dispose()
            }
            chain.proceed()
        }
        // A partially installed adapter must never leave a hidden but launchable button.
        ready = installed.all { it }
        return if (ready) installed.size + flashHooks else 0
    }

    companion object {
        fun install(loader: ClassLoader, hostLoader: ClassLoader, settings: HookSettings): Int = LockscreenShortcutHooks(loader, hostLoader, settings).install(loader)
    }
}
