/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * ChargingCVP adapted to OS4's indication area without replacing its rotation or messages.
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.LockscreenChargingSettings as Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

@TargetApi(28)
internal class LockscreenChargingHooks private constructor(loader: ClassLoader, settings: HookSettings) {
    private val interval = Settings.interval(settings.string(Settings.INTERVAL), settings.isOn(Settings.CUSTOM_INTERVAL))
    private val milliamps = settings.isOn(Settings.MILLIAMPS)
    private val temperature = settings.isOn(Settings.TEMPERATURE)
    private val type = requireNotNull(Reflect.loadClass(loader, LockscreenChargingTargets.CONTROLLER))
    private fun field(name: String) = requireNotNull(Reflect.findField(type, name))
    private val area = field("mIndicationArea")
    private val text = field("mLockScreenIndicationView")
    private val rotate = field("mRotateTextViewController")
    private val visible = field("mVisible")
    private val dozing = field("mDozing")
    private val plugged = field("mPowerPluggedIn")
    private val defender = field("mBatteryDefender")
    private val injector = field("mKeyguardIndicationInjectorLazy")
    private val lazyGet = requireNotNull(Reflect.loadClass(loader, "dagger.Lazy")).getDeclaredMethod("get")
    private val reverse = requireNotNull(Reflect.findField(requireNotNull(Reflect.loadClass(loader, LockscreenChargingTargets.INJECTOR)), "mReverseChargingState"))
    private val currentType = requireNotNull(Reflect.findField(rotate.type, "mCurrIndicationType"))
    private val currentMessage = requireNotNull(Reflect.findField(rotate.type, "mCurrMessage"))
    private val reposition = requireNotNull(Reflect.findField(requireNotNull(Reflect.loadClass(loader, "com.miui.charge.ChargeUtils")), "sNeedRepositionDevice"))
    private val tiny = requireNotNull(NotificationHooks.resolve(loader, SystemUiTargets.flipTinyScreen))
    private val bindings = WeakHashMap<Any, WeakReference<Binding>>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(2)).apply { allowCoreThreadTimeOut(true) }
    private var ready = false
    private var hostVersion: Long? = null

    private inner class Binding(owner: Any, val anchor: TextView, val container: LinearLayout) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val owner = WeakReference(owner)
        private var observer: ViewTreeObserver? = null
        private val detail = TextView(anchor.context).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            isClickable = false; isFocusable = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        private val requests = ChargingRequestState()
        private var task: FutureTask<Unit>? = null
        private var sample: ChargingSample? = null
        private var active = false
        private var resumeAfter = 0L
        private var disposed = false
        private val tick = Runnable { guarded { refresh() } }

        fun start() { anchor.addOnAttachStateChangeListener(this); if (anchor.isAttachedToWindow) onViewAttachedToWindow(anchor) }

        private fun owns(): Boolean = owner.get()?.let {
            area.get(it) === container && text.get(it) === anchor && anchor.parent === container
        } == true

        private fun eligible(): Boolean {
            val value = owner.get() ?: return false
            val rotation = rotate.get(value) ?: return false
            val message = currentMessage.get(rotation) as? CharSequence
            return owns() && LockscreenChargingPolicy.eligible(hostVersion ?: -1,
                anchor.isAttachedToWindow, anchor.isShown && anchor.windowVisibility == View.VISIBLE && anchor.alpha > 0f,
                visible.getBoolean(value), dozing.getBoolean(value), SafeModeRuntime.blocked,
                tiny.invoke(null, anchor.context) != false, plugged.getBoolean(value), defender.getBoolean(value),
                reverse.getInt(lazyGet.invoke(injector.get(value))), reposition.getBoolean(null), currentType.getInt(rotation),
                !message.isNullOrEmpty() && TextUtils.equals(message, anchor.text))
        }

        private fun deviceAwakeAndLocked(): Boolean =
            anchor.context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                anchor.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        private fun refresh() {
            main.removeCallbacks(tick)
            if (disposed) return
            if (!owns() || SafeModeRuntime.blocked) { dispose(); return }
            if (!eligible() || !deviceAwakeAndLocked()) {
                stop(); resumeAfter = SystemClock.elapsedRealtime() + 1_000L
                return
            }
            active = true
            render()
            val token = requests.begin(SystemClock.elapsedRealtime())
            if (token != null) {
                val weak = WeakReference(this)
                val context = anchor.context.applicationContext
                val handler = main
                task = FutureTask(java.util.concurrent.Callable {
                    val result = sampleBattery(context)
                    handler.post { weak.get()?.let { binding -> binding.guarded { binding.finish(token, result) } } }
                    Unit
                })
                try { worker.execute(task!!) } catch (_: RejectedExecutionException) {
                    task = null; requests.complete(token, SystemClock.elapsedRealtime(), interval)
                }
            }
            main.postDelayed(tick, interval)
        }

        private fun finish(token: Long, result: ChargingSample?) {
            if (!requests.complete(token, SystemClock.elapsedRealtime(), interval)) return
            task = null
            if (!eligible() || !deviceAwakeAndLocked()) {
                stop(); resumeAfter = SystemClock.elapsedRealtime() + 1_000L
                return
            }
            sample = result; render()
            main.removeCallbacks(tick); main.postDelayed(tick, interval)
        }

        private fun render() {
            val locale = anchor.resources.configuration.locales[0]
            val value = LockscreenChargingPolicy.details(sample, SystemClock.elapsedRealtime(), interval, milliamps, temperature, locale)
            if (value == null) { detail.visibility = View.GONE; return }
            if (detail.parent == null) container.addView(detail, container.indexOfChild(anchor) + 1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (detail.parent !== container) return
            if (!TextUtils.equals(detail.text, value)) detail.text = value
            if (detail.textColors != anchor.textColors) detail.setTextColor(anchor.textColors)
            if (detail.textSize != anchor.textSize) detail.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, anchor.textSize)
            if (detail.typeface !== anchor.typeface) detail.typeface = anchor.typeface
            detail.alpha = anchor.alpha
            detail.visibility = View.VISIBLE
        }

        private fun stop() {
            active = false; requests.cancel(); sample = null
            main.removeCallbacks(tick)
            task?.let { it.cancel(false); worker.remove(it) }; task = null
            detail.visibility = View.GONE
        }

        fun dispose() {
            if (disposed) return
            disposed = true; stop()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            anchor.removeOnAttachStateChangeListener(this)
            (detail.parent as? ViewGroup)?.removeView(detail)
            owner.clear()
        }

        private fun guarded(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                ModuleLog.error("${Settings.FEATURE}: binding failed", failure)
                runCatching { dispose() }
                SafeModeRuntime.trip("Hook failed: lockscreen charging info")
            }
        }

        override fun onViewAttachedToWindow(v: View) = guarded {
            if (disposed) return@guarded
            if (observer == null) observer = anchor.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            refresh()
        }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            stop()
            (detail.parent as? ViewGroup)?.removeView(detail)
        }
        override fun onPreDraw(): Boolean {
            guarded {
                if (!owns() || SafeModeRuntime.blocked) dispose()
                else if (!eligible()) { if (active) stop() }
                else if (!active && SystemClock.elapsedRealtime() >= resumeAfter) refresh()
                else if (active && detail.alpha != anchor.alpha) detail.alpha = anchor.alpha
            }
            return true
        }
    }

    private fun install(loader: ClassLoader): Int {
        val method = requireNotNull(NotificationHooks.resolve(loader, LockscreenChargingTargets.area))
        val callers = LockscreenChargingTargets.callers.map { requireNotNull(NotificationHooks.resolve(loader, it)) }
        if (!callers.map { HookRuntime.deoptimize(it, "${Settings.FEATURE}/${it.name}") }.all { it }) return 0
        ready = HookRuntime.hook(method, "${Settings.FEATURE}/setIndicationArea") { chain ->
            if (!ready || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
            val owner = chain.thisObject!!
            bindings.remove(owner)?.get()?.dispose()
            val result = chain.proceed()
            val anchor = text.get(owner) as? TextView ?: return@hook result
            val container = area.get(owner) as? LinearLayout ?: return@hook result
            val version = hostVersion ?: anchor.context.packageManager.getPackageInfo("com.android.systemui", 0).longVersionCode.also { hostVersion = it }
            if (version == 202602260L && anchor.parent === container && container.orientation == LinearLayout.VERTICAL) {
                val binding = Binding(owner, anchor, container)
                bindings[owner] = WeakReference(binding)
                try { binding.start() } catch (failure: Throwable) { binding.dispose(); throw failure }
            }
            result
        }
        return if (ready) 1 else 0
    }

    companion object {
        fun install(loader: ClassLoader, settings: HookSettings): Int = LockscreenChargingHooks(loader, settings).install(loader)

        private fun sampleBattery(context: Context): ChargingSample? = runCatching {
            // Sticky read only: no receiver is registered and no sysfs I/O runs on main.
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            fun extra(key: String): Int? = if (battery.hasExtra(key)) battery.getIntExtra(key, Int.MIN_VALUE) else null
            val current = runCatching { context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
            ChargingSample(current, extra(BatteryManager.EXTRA_VOLTAGE), extra(BatteryManager.EXTRA_TEMPERATURE),
                extra(BatteryManager.EXTRA_PLUGGED) ?: 0, extra(BatteryManager.EXTRA_STATUS) ?: 0,
                extra(BatteryManager.EXTRA_HEALTH) ?: 0, SystemClock.elapsedRealtime())
        }.getOrNull()
    }
}
