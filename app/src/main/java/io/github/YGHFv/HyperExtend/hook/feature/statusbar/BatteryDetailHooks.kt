/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.BatteryDetailSettings as Settings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ChargingRequestState
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal class BatteryDetailHooks private constructor(loader: ClassLoader, settings: HookSettings) {
    private val field = BatteryDetailTargets.fields.single().let { (owner, name, type) ->
        StatusIconHost.field(loader, owner, name, type)
    }
    private val host = StatusIconHost()
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private val main = Handler(Looper.getMainLooper())
    private val interval = settings.number("${Settings.FEATURE}.update_spacing", 2, 1, 10) * 1_000L
    private val format = BatteryDetailFormat(
        settings.number("${Settings.FEATURE}.content", 1, 1, 5),
        settings.number("${Settings.FEATURE}.hide_unit", 0, 0, 3),
        settings.isOn("${Settings.FEATURE}.temp_decimal"), settings.isOn("${Settings.FEATURE}.positive"),
        settings.isOn("${Settings.FEATURE}.fix_current_ratio"), settings.isOn("${Settings.FEATURE}.single_row"),
        settings.isOn("${Settings.FEATURE}.reverse_order"), settings.isOn("${Settings.FEATURE}.in_charge"))
    private val size = settings.number("${Settings.FEATURE}.font_size", 16, 14, 40) * 0.5f
    private val bold = settings.isOn("${Settings.FEATURE}.bold")
    private val align = settings.number("${Settings.FEATURE}.align", 1, 1, 4)
    private val width = settings.number("${Settings.FEATURE}.fixed_width", 10, 10, 120)
    private val left = settings.number("${Settings.FEATURE}.left_margin", 8, 0, 10) * 0.5f
    private val right = settings.number("${Settings.FEATURE}.right_margin", 8, 0, 10) * 0.5f
    private val top = (settings.number("${Settings.FEATURE}.vertical_offset", 8, 0, 20) - 8) * 0.5f
    // Created only on the first visible sample; the idle thread expires, with no perpetual poller.
    private val worker by lazy {
        ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue(2),
            { task -> Thread(task, "HyperExtend-battery-detail").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }

    private inner class Binding(val root: View, val clock: TextView, val parent: LinearLayout) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val label = TextView(clock.context).apply {
            isClickable = false; isFocusable = false
            includeFontPadding = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            maxLines = if (format.single || format.content in 2..3) 1 else 2
            setLineSpacing(0f, if (size > 8.5f) 0.85f else 0.9f)
            gravity = Gravity.CENTER_VERTICAL or when (align) {
                3 -> Gravity.CENTER_HORIZONTAL; 4 -> Gravity.END; else -> Gravity.START
            }
        }
        private val requests = ChargingRequestState()
        private var task: FutureTask<Unit>? = null
        private var observer: ViewTreeObserver? = null
        private var sample: BatteryDetailSample? = null
        private var active = false
        private var disposed = false
        private var retryAt = 0L
        private val tick = Runnable { guarded { refresh() } }
        private fun enabled() = !SafeModeRuntime.blocked && HookRuntime.settings().isOn(Settings.FEATURE)
        private fun owns() = field.get(root) === parent && clock.parent === parent &&
            parent.id == BatteryDetailTargets.LEFT && root.findViewById<View>(BatteryDetailTargets.CLOCK) === clock

        private fun visible(): Boolean {
            if (!enabled() || !host.eligible(root) || !root.isAttachedToWindow || !clock.isShown ||
                root.windowVisibility != View.VISIBLE || !owns()) return false
            var node: View? = clock
            while (node != null) {
                if (node.alpha <= 0f || node.visibility != View.VISIBLE) return false
                if (node === root) break
                node = node.parent as? View
            }
            return node === root &&
                root.context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                root.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        }

        fun start() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
        }
        private fun refresh() {
            main.removeCallbacks(tick)
            if (!enabled() || !owns()) { dispose(); return }
            if (!visible()) { stop(); return }
            active = true
            render()
            val token = requests.begin(SystemClock.elapsedRealtime())
            if (token != null) {
                val weak = WeakReference(this)
                val context = root.context.applicationContext
                val handler = main
                val work = FutureTask(java.util.concurrent.Callable {
                    val result = sampleBattery(context)
                    handler.post { weak.get()?.let { binding ->
                        binding.guarded { binding.finish(token, result) }
                    } }
                    Unit
                })
                task = work
                try { worker.execute(work) } catch (_: java.util.concurrent.RejectedExecutionException) {
                    task = null
                    requests.complete(token, SystemClock.elapsedRealtime(), interval)
                }
            }
            main.postDelayed(tick, interval)
        }
        private fun finish(token: Long, result: BatteryDetailSample?) {
            if (!requests.complete(token, SystemClock.elapsedRealtime(), interval)) return
            task = null
            if (!visible()) { stop(); return }
            sample = result
            render()
            main.removeCallbacks(tick)
            main.postDelayed(tick, interval)
        }
        private fun render() {
            val density = root.resources.displayMetrics.density
            val params = parent.layoutParams
            val fixed = when {
                params.width > 0 -> params.width
                params.width == ViewGroup.LayoutParams.MATCH_PARENT ||
                    (params as? LinearLayout.LayoutParams)?.weight?.let { it > 0f } == true -> parent.width
                else -> null
            }
            var occupied = parent.paddingLeft + parent.paddingRight
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child === label || child.visibility == View.GONE) continue
                val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
                occupied += child.measuredWidth + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
            }
            val available = BatteryDetailPolicy.width(root.width, fixed, occupied, density)
            val value = BatteryDetailPolicy.text(sample, SystemClock.elapsedRealtime(), interval,
                format, root.resources.configuration.locales[0])
            if (value == null || available < (size * 3 * density).roundToInt()) {
                label.visibility = View.GONE
                return
            }
            if (label.parent == null) parent.addView(label, parent.indexOfChild(clock) + 1,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (label.parent !== parent) { dispose(); return }
            val desired = if (width == 10) ViewGroup.LayoutParams.WRAP_CONTENT else minOf((width * density).roundToInt(), available)
            if (label.layoutParams.width != desired) label.layoutParams = label.layoutParams.apply { this.width = desired }
            if (label.maxWidth != available) label.maxWidth = available
            if (!TextUtils.equals(label.text, value)) label.text = value
            if (label.textColors != clock.textColors) label.setTextColor(clock.textColors)
            val font = if (bold) Typeface.create(clock.typeface, Typeface.BOLD) else clock.typeface
            if (label.typeface != font) label.typeface = font
            if (label.textSize != size * density) label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
            val start = (left * density).roundToInt()
            val end = (right * density).roundToInt()
            if (label.paddingStart != start || label.paddingEnd != end) label.setPaddingRelative(start, 0, end, 0)
            label.translationY = top * density
            label.alpha = clock.alpha
            label.visibility = View.VISIBLE
        }
        private fun stop() {
            active = false; sample = null; requests.cancel()
            retryAt = SystemClock.elapsedRealtime() + 1_000
            main.removeCallbacks(tick)
            task?.let { it.cancel(false); worker.remove(it) }; task = null
            label.visibility = View.GONE
            if (label.parent === parent) parent.removeView(label)
        }
        fun dispose() {
            if (disposed) return
            disposed = true
            stop()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            root.removeOnAttachStateChangeListener(this)
            if (label.parent === parent) parent.removeView(label)
        }
        private fun guarded(block: () -> Unit) {
            if (disposed) return
            try { block() } catch (error: Throwable) {
                ModuleLog.error("${Settings.FEATURE}: binding failed", error)
                runCatching { dispose() }
            }
        }
        override fun onViewAttachedToWindow(v: View) = guarded {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            refresh()
        }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            stop()
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            if (label.parent === parent) parent.removeView(label)
        }
        override fun onPreDraw(): Boolean {
            guarded {
                if (!enabled() || !owns()) dispose()
                else if (!visible()) { if (active) stop() }
                else if (!active && SystemClock.elapsedRealtime() >= retryAt) refresh()
                else if (active) render()
            }
            return true
        }
    }

    private fun install(loader: ClassLoader): Int {
        val method = NotificationHooks.resolve(loader, BatteryDetailTargets.inflate) ?: return 0
        if (!HookRuntime.deoptimize(method, "${Settings.FEATURE}/inflate")) return 0
        return if (HookRuntime.hook(method, "${Settings.FEATURE}/inflate") { chain ->
            val root = chain.thisObject as? View
            if (root == null || !host.eligible(root)) return@hook chain.proceed()
            bindings.remove(root)?.get()?.dispose()
            val result = chain.proceed()
            val clock = root.findViewById<TextView>(BatteryDetailTargets.CLOCK)
            val parent = field.get(root) as? LinearLayout
            if (clock != null && parent != null && parent.javaClass == LinearLayout::class.java &&
                parent.orientation == LinearLayout.HORIZONTAL && clock.parent === parent) {
                val binding = Binding(root, clock, parent)
                bindings[root] = WeakReference(binding)
                try { binding.start() } catch (error: Throwable) { binding.dispose(); throw error }
            }
            result
        }) 1 else 0
    }

    companion object {
        fun install(loader: ClassLoader, settings: HookSettings): Int =
            BatteryDetailHooks(loader, settings).install(loader)

        private fun sampleBattery(context: Context): BatteryDetailSample? = runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            fun extra(key: String) = if (intent.hasExtra(key)) intent.getIntExtra(key, Int.MIN_VALUE) else null
            val current = runCatching { context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
            BatteryDetailSample(current, extra(BatteryManager.EXTRA_VOLTAGE), extra(BatteryManager.EXTRA_TEMPERATURE),
                extra(BatteryManager.EXTRA_STATUS) ?: 0, extra(BatteryManager.EXTRA_PLUGGED) ?: 0, SystemClock.elapsedRealtime())
        }.getOrNull()
    }
}
