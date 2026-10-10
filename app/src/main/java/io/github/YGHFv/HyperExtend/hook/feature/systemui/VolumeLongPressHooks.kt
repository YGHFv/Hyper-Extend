/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.volume.AppVolumeEntryHooks
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object VolumeLongPressHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int = Adapter(loader, settings).install()

    private class Adapter(val loader: ClassLoader, val settings: HookSettings) {
        val targets = VolumeLongPressTargets
        val methods = mutableMapOf<SystemUiMethod, Method>()
        val fields = mutableMapOf<String, Field>()
        val sessions = WeakHashMap<View, Session>()
        val handler = Handler(Looper.getMainLooper())
        val ready = AtomicBoolean(false)
        val logged = AtomicBoolean(false)
        var nativeCancel = false
        lateinit var listenerType: Class<*>
        lateinit var motionType: Class<*>
        lateinit var dialogType: Class<*>

        fun install(): Int {
            for (spec in targets.methods) methods[spec] = NotificationHooks.resolve(loader, spec) ?: return 0
            for ((owner, name, type) in targets.fields) fields[name] = Class.forName(owner, false, loader)
                .getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
            listenerType = Class.forName(targets.LISTENER, false, loader)
            motionType = Class.forName(targets.MOTION, false, loader)
            dialogType = Class.forName(targets.DIALOG, false, loader)
            if (!methods.values.map { HookRuntime.deoptimize(it, "${VolumeLongPressPolicy.FEATURE}/caller") }.all { it }) return 0
            var count = 0
            if (HookRuntime.hook(methods.getValue(targets.dispatch), "${VolumeLongPressPolicy.FEATURE}/touch") { chain ->
                    if (nativeCancel || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                    val seek = chain.thisObject as SeekBar
                    val event = chain.args[0] as MotionEvent
                    val action = event.actionMasked
                    if (action == MotionEvent.ACTION_DOWN) {
                        sessions.remove(seek)?.dispose()
                        val binding = if (ready.get() && finger(event)) binding(seek) else null
                        if (binding != null && event.x in 0f..seek.width.toFloat() && event.y in 0f..seek.height.toFloat()) {
                            val session = Session(seek, binding, event)
                            sessions[seek] = session
                            session.attach()
                        }
                    }
                    val session = sessions[seek]
                    session?.observe(event)
                    // Capture the unmodified event before RelativeSeekBarInjector/VerticalSeekBar.
                    val drain = session?.hold?.fired == true
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        sessions.remove(seek)?.dispose()
                    }
                    try {
                        val result = if (drain) true else chain.proceed()
                        if (!drain && session != null && seek.progress != session.progress) session.cancelPending()
                        if (result != true) sessions.remove(seek)?.dispose()
                        result
                    } catch (failure: Throwable) {
                        sessions.remove(seek)?.dispose()
                        throw failure
                    }
                }) count++
            if (HookRuntime.hook(methods.getValue(targets.dialogDispatch), "${VolumeLongPressPolicy.FEATURE}/pointers") { chain ->
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        val event = chain.args[0] as MotionEvent
                        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL)
                            forDialog(chain.thisObject) { it.cancelPending() }
                    }
                    chain.proceed()
                }) count++
            for (spec in targets.lifecycle) {
                if (HookRuntime.hook(methods.getValue(spec), "${VolumeLongPressPolicy.FEATURE}/${spec.name}") { chain ->
                        if (Looper.myLooper() == Looper.getMainLooper()) forDialog(chain.thisObject) { it.cancelPending() }
                        chain.proceed()
                    }) count++
            }
            ready.set(count == 7)
            return if (ready.get()) count else 0
        }

        fun forDialog(dialog: Any?, block: (Session) -> Unit) =
            sessions.values.toList().filter { it.dialog.get() === dialog }.forEach(block)

        fun finger(event: MotionEvent) = event.pointerCount == 1 &&
            event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER

        class Binding(val motion: Any, val dialog: View, val click: View.OnClickListener, val button: View, val callback: Any)

        fun binding(seek: SeekBar): Binding? = runCatching {
            if (!ready.get() || SafeModeRuntime.blocked || !settings.isOn(VolumeLongPressPolicy.FEATURE)) return@runCatching null
            val listener = fields.getValue("mSeekBarOnclickListener").get(seek) ?: return@runCatching null
            if (!listenerType.isInstance(listener)) return@runCatching null
            val motion = fields.getValue("a").get(listener) ?: return@runCatching null
            if (!motionType.isInstance(motion)) return@runCatching null
            val dialog = fields.getValue("mVolumeView").get(motion) as? View ?: return@runCatching null
            if (!dialogType.isInstance(dialog) || fields.getValue("mMotion").get(dialog) !== motion) return@runCatching null
            val callback = fields.getValue("mCallback").get(dialog) ?: return@runCatching null
            val click = fields.getValue("expandListener").get(dialog) as? View.OnClickListener ?: return@runCatching null
            val button = fields.getValue("mExpandButton").get(dialog) as? View ?: return@runCatching null
            val context = seek.context
            val pm = context.packageManager
            val accessibility = context.getSystemService(AccessibilityManager::class.java) ?: return@runCatching null
            val supported = VolumeLongPressPolicy.eligible(true,
                pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode,
                seek.display?.displayId == 0 && dialog.display?.displayId == 0,
                seek.isAttachedToWindow && dialog.isAttachedToWindow,
                seek.isShown && seek.isEnabled && dialog.isShown && dialog.windowVisibility == View.VISIBLE &&
                    button.isShown && button.isEnabled && fields.getValue("mNeedShowDialog").getBoolean(dialog),
                fields.getValue("mExpanded").getBoolean(motion) || methods.getValue(targets.expanded).invoke(dialog) == true,
                methods.getValue(targets.animating).invoke(dialog) == true || methods.getValue(targets.showing).invoke(dialog) == true,
                fields.getValue("isControlCenterPanel").getBoolean(dialog), accessibility.isTouchExplorationEnabled,
                AppVolumeEntryHooks.ownsExpandedPanel(dialog), fields.getValue("mVolumeSeekBar").get(motion) === seek,
                fields.getValue("mIsExpandButton").getBoolean(motion))
            if (supported) Binding(motion, dialog, click, button, callback) else null
        }.getOrNull()

        inner class Session(seek: SeekBar, binding: Binding, event: MotionEvent) : View.OnAttachStateChangeListener {
            val seek = WeakReference(seek)
            val dialog = WeakReference(binding.dialog)
            val motion = WeakReference(binding.motion)
            val callback = WeakReference(binding.callback)
            val click = WeakReference(binding.click)
            val configuration = Configuration(seek.resources.configuration)
            val progress = seek.progress
            val width = seek.width
            val height = seek.height
            val hold = VolumeHold(event.eventTime, event.rawX, event.rawY, event.getPointerId(0),
                ViewConfiguration.get(seek.context).scaledTouchSlop.toFloat())
            var snapshot: MotionEvent? = MotionEvent.obtain(event)
            val timeout = Runnable {
                runCatching { fire() }.onFailure {
                    cancelPending()
                    ModuleLog.error("${VolumeLongPressPolicy.FEATURE}: hold cancelled after callback failure", it)
                }
            }

            fun attach() {
                seek.get()?.addOnAttachStateChangeListener(this)
                dialog.get()?.addOnAttachStateChangeListener(this)
                if (!handler.postAtTime(timeout, hold.start + VolumeLongPressPolicy.DELAY)) cancelPending()
            }
            fun observe(event: MotionEvent) {
                if (hold.fired) return
                if (!finger(event) || event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE)) {
                    cancelPending(); return
                }
                val dx = event.rawX - event.x
                val dy = event.rawY - event.y
                for (i in 0 until event.historySize) hold.sample(event.getHistoricalEventTime(i),
                    event.getHistoricalX(0, i) + dx, event.getHistoricalY(0, i) + dy, event.getPointerId(0), event.pointerCount)
                hold.sample(event.eventTime, event.rawX, event.rawY, event.getPointerId(0), event.pointerCount)
                if (hold.cancelled) { cancelPending(); return }
                snapshot?.recycle()
                snapshot = MotionEvent.obtain(event)
            }
            fun cancelPending() {
                hold.cancel()
                handler.removeCallbacks(timeout)
                snapshot?.recycle(); snapshot = null
            }
            fun dispose() {
                cancelPending()
                seek.get()?.removeOnAttachStateChangeListener(this)
                dialog.get()?.removeOnAttachStateChangeListener(this)
            }
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                seek.get()?.let { if (sessions[it] === this) sessions.remove(it) }
                dispose()
            }
            fun fire() {
                val view = seek.get() ?: return dispose()
                val binding = binding(view)
                if (sessions[view] !== this || binding == null || binding.motion !== motion.get() ||
                    binding.dialog !== dialog.get() || binding.callback !== callback.get() || binding.click !== click.get() ||
                    view.progress != progress || view.width != width || view.height != height ||
                    view.resources.configuration != configuration || !hold.fire(SystemClock.uptimeMillis())) {
                    cancelPending(); return
                }
                val cancel = snapshot ?: return cancelPending()
                snapshot = null
                try {
                    // End native tracking/press animation once before expansion; no synthetic click
                    // or UP can commit a second volume change. Drain the remaining physical stream.
                    cancel.action = MotionEvent.ACTION_CANCEL
                    nativeCancel = true
                    try { view.dispatchTouchEvent(cancel) } finally { nativeCancel = false }
                    val current = binding(view)
                    if (!hold.cancelled && sessions[view] === this && current != null &&
                        current.dialog === binding.dialog && current.motion === binding.motion &&
                        current.callback === binding.callback && current.click === binding.click) {
                        binding.click.onClick(binding.button)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (logged.compareAndSet(false, true)) ModuleLog.info("${VolumeLongPressPolicy.FEATURE}: native expansion requested (device acceptance pending)")
                    }
                } catch (failure: Throwable) {
                    ModuleLog.error("${VolumeLongPressPolicy.FEATURE}: expansion failed; native stream already cancelled", failure)
                } finally { cancel.recycle() }
            }
        }
    }
}
