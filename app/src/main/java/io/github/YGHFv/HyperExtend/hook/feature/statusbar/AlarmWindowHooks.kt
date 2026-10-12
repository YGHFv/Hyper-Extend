/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ChargingRequestState
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AlarmWindowHooks private constructor(loader: ClassLoader) {
    private val t = AlarmWindowTargets
    private val methods = (listOf(t.visible, t.capture, t.user, t.userContext) + t.callers).associateWith {
        requireNotNull(NotificationHooks.resolve(loader, it))
    }
    private val fields = t.fields.map { (owner, name, type) -> StatusIconHost.field(loader, owner, name, type) }
    private val main = Handler(Looper.getMainLooper())
    private val host = StatusIconHost()
    private val ready = AtomicBoolean(false)
    @Volatile private var tracker = WeakReference<Any>(null)
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private val worker by lazy {
        ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(4),
            { task -> Thread(task, "HyperExtend-alarm-window").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
    private fun hours(): Int {
        if (SafeModeRuntime.blocked || !ready.get()) return 0
        val settings = HookRuntime.settings()
        return if (settings.isOn("status_bar_icons") && settings.number(MODE, 0) == 3)
            settings.number(HOURS, 0, 0, 48) else 0
    }
    private fun currentUser(value: Any): Int? = methods.getValue(t.user).invoke(value) as? Int

    private data class Reading(val user: Int, val trigger: Long?, val elapsed: Long)
    private inner class Binding(view: View) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val viewRef = WeakReference(view)
        private val requests = ChargingRequestState()
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var reading: Reading? = null
        private var task: FutureTask<Unit>? = null
        private var active = false
        private var disposed = false
        private var hidden = false
        private var retryAt = 0L
        private val tick = Runnable { guarded { refresh() } }

        fun start() {
            val view = viewRef.get() ?: return
            view.addOnAttachStateChangeListener(this)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }
        private fun usable(): Boolean {
            val view = viewRef.get() ?: return false
            val parent = view.parent as? View ?: return false
            // Do not use this icon's own alpha/visibility: the native layout may hide it
            // because of our query result. Only ancestor lifecycle controls sampling.
            if (!view.isAttachedToWindow || !host.eligible(view) || !parent.isShown ||
                parent.windowVisibility != View.VISIBLE || hours() == 0) return false
            var node: View? = parent
            while (node != null) {
                if (node.alpha <= 0f) return false
                node = node.parent as? View
            }
            return view.context.getSystemService(PowerManager::class.java)?.isInteractive == true
        }
        fun shouldHide(): Boolean {
            if (disposed || hours() == 0) return false
            val value = reading ?: return false
            val current = tracker.get() ?: return false
            if (currentUser(current) != value.user) return false
            return AlarmWindowPolicy.hide(value.trigger, System.currentTimeMillis(), hours(),
                value.elapsed, SystemClock.elapsedRealtime())
        }
        private fun remeasure() {
            val value = shouldHide()
            if (hidden == value) return
            hidden = value
            (viewRef.get()?.parent as? View)?.requestLayout()
        }
        private fun refresh() {
            main.removeCallbacks(tick)
            if (hours() == 0) { dispose(); return }
            if (!usable()) { stop(); return }
            val view = viewRef.get() ?: return
            val current = tracker.get()
            if (current == null) {
                active = false; retryAt = SystemClock.elapsedRealtime() + 1_000
                return
            }
            active = true
            remeasure()
            val token = requests.begin(SystemClock.elapsedRealtime())
            if (token != null) {
                val user = currentUser(current)
                if (user == null) { stop(); return }
                val context = methods.getValue(t.userContext).invoke(current, view.context.applicationContext) as? Context
                if (context == null) { stop(); return }
                if (currentUser(current) != user) { stop(); return }
                val weak = WeakReference(this)
                val handler = main
                val work = FutureTask(java.util.concurrent.Callable {
                    val result = readTrigger(context)
                    val elapsed = SystemClock.elapsedRealtime()
                    handler.post { weak.get()?.let { binding ->
                        binding.guarded { binding.finish(token, Reading(user, result, elapsed)) }
                    } }
                    Unit
                })
                task = work
                try { worker.execute(work) } catch (_: java.util.concurrent.RejectedExecutionException) {
                    task = null; requests.complete(token, SystemClock.elapsedRealtime(), 30_000)
                }
            }
            main.postDelayed(tick, 30_000)
        }
        private fun finish(token: Long, value: Reading) {
            if (!requests.complete(token, SystemClock.elapsedRealtime(), 30_000)) return
            task = null
            val current = tracker.get()
            if (!usable() || current == null || currentUser(current) != value.user) { stop(); return }
            reading = value
            remeasure()
            main.removeCallbacks(tick); main.postDelayed(tick, 30_000)
        }
        private fun stop() {
            active = false; requests.cancel(); reading = null
            retryAt = SystemClock.elapsedRealtime() + 1_000
            main.removeCallbacks(tick)
            task?.let { it.cancel(false); worker.remove(it) }; task = null
            remeasure()
        }
        private fun dispose() {
            if (disposed) return
            disposed = true
            stop()
            observer.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer.clear()
            viewRef.get()?.removeOnAttachStateChangeListener(this)
        }
        private fun guarded(block: () -> Unit) {
            if (disposed) return
            try { block() } catch (error: Throwable) {
                ModuleLog.error("status_bar_icons/alarm window failed; returning native", error)
                runCatching { dispose() }
            }
        }
        override fun onViewAttachedToWindow(v: View) = guarded {
            observer.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = WeakReference(v.viewTreeObserver.also { it.addOnPreDrawListener(this) })
            refresh()
        }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            stop()
            observer.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer.clear()
        }
        override fun onPreDraw(): Boolean {
            guarded {
                if (hours() == 0) dispose()
                else if (!usable()) { if (active) stop() }
                else {
                    val value = reading
                    val current = tracker.get()
                    if (value != null && (current == null || currentUser(current) != value.user)) stop()
                    remeasure()
                    if (!active && SystemClock.elapsedRealtime() >= retryAt) refresh()
                }
            }
            return true
        }
    }
    private fun install(): Int {
        if (!(listOf(t.visible, t.capture) + t.callers).all {
                HookRuntime.deoptimize(methods.getValue(it), "status_bar_icons/alarm-caller")
            }) return 0
        val capture = HookRuntime.hookAfter(methods.getValue(t.capture), "status_bar_icons/alarm-user") { chain, result ->
            if (ready.get()) {
                val value = fields[1].get(chain.thisObject)
                if (value != null && methods.getValue(t.user).declaringClass.isInstance(value)) tracker = WeakReference(value)
            }
            result
        }
        val visible = HookRuntime.hookAfter(methods.getValue(t.visible), "status_bar_icons/alarm-visible") { chain, result ->
            val view = chain.thisObject as? View ?: return@hookAfter result
            if (!ready.get() || !host.eligible(view) || fields[0].get(view) != "alarm_clock" || hours() == 0)
                return@hookAfter result
            val binding = bindings[view]?.get() ?: Binding(view).also {
                bindings[view] = WeakReference(it)
                it.start()
            }
            if (result == true && binding.shouldHide()) false else result
        }
        ready.set(capture && visible)
        return if (ready.get()) 2 else 0
    }
    companion object {
        const val MODE = "status_bar_icons.alarm_clock"
        const val HOURS = "status_bar_icons.alarm_clock_hours"
        fun install(loader: ClassLoader, settings: HookSettings): Int =
            if (settings.number(MODE, 0) == 3 && settings.number(HOURS, 0, 0, 48) > 0)
                AlarmWindowHooks(loader).install() else 0

        private fun readTrigger(context: Context): Long? = runCatching {
            // Current native observer reads this per-user epoch, not a locale-formatted weekday.
            // Read-only: no AlarmManager scheduling and no settings/receiver registration.
            val miui = Settings.Secure.getString(context.contentResolver, "locked_screen_alarm_status")
                ?.toLongOrNull()?.takeIf { it > System.currentTimeMillis() }
            miui ?: context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        }.getOrNull()
    }
}
