/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 功能来源：西米露 / HyperCeiler · DoubleTapToSleep（AGPL-3.0）。
 * 自定义动作参考 XiaomiHelper · StatusBarTapGesture（GPL-3.0-or-later）。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import io.github.YGHFv.HyperExtend.core.StatusBarGestureSettings
import io.github.YGHFv.HyperExtend.core.StatusBarGestureSettings.Selection
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 自定义状态栏单击和双击动作。作用域：com.android.systemui。 */
internal object StatusBarGestures {

    private const val FEATURE = StatusBarGestureSettings.FEATURE
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = StatusBarGestureTargets
        val methods = (listOf(t.touch, t.dispatch, t.nativeDoubleTap, t.nativeSingleTap,
            t.detectorTouch, t.detectorMessage) + t.callers)
            .associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val gate = StatusBarGestureHostGate(loader)
        val actions = StatusBarGestureActions(loader)
        val main = Handler(Looper.getMainLooper())
        fun selection() = StatusBarGestureSettings.selection(settings.string(StatusBarGestureSettings.SINGLE),
            settings.string(StatusBarGestureSettings.DOUBLE))
        fun enabled() = settings.isOn(FEATURE) && selection().custom
        fun usable(view: View, selected: Selection): Boolean = runCatching {
            enabled() && selection() == selected && !SafeModeRuntime.blocked &&
                view.isAttachedToWindow && view.isShown && view.windowVisibility == View.VISIBLE &&
                view.display?.displayId == 0 && gate.handler.get(view) != null &&
                view.context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == false &&
                gate.permits(view)
        }.getOrDefault(false)
        if (!(listOf(t.touch, t.dispatch, t.detectorTouch, t.detectorMessage) + t.callers).all {
                HookRuntime.deoptimize(methods.getValue(it), "$FEATURE/caller")
            }) return 0
        val doubleTapScope = StatusBarDoubleTapScope()
        // 原生在第二次 DOWN 就启动滚动协程，必须在 onTouchEvent 执行期间拦截，而不是息屏后改返回值。
        if (!HookRuntime.hook(methods.getValue(t.nativeDoubleTap), "$FEATURE/nativeDoubleTap") { chain ->
                val owner = gate.listenerDetector.get(chain.thisObject)
                val event = chain.args.getOrNull(0) as? MotionEvent
                val enabled = enabled()
                if (doubleTapScope.suppresses(owner, event, enabled, SafeModeRuntime.blocked) ||
                    (event != null && doubleTapScope.suppressesConsumedTap(owner, event.downTime,
                        enabled, SafeModeRuntime.blocked))) true
                else chain.proceed()
            }) return 0
        // 原生可能把模块认可的双击判成两次单击，TAP 消息会在息屏后才确认，不能仅靠线程上下文拦截。
        if (!HookRuntime.hook(methods.getValue(t.nativeSingleTap), "$FEATURE/nativeSingleTap") { chain ->
                val event = chain.args.getOrNull(0) as? MotionEvent
                if (event != null && doubleTapScope.suppressesConsumedTap(
                        gate.listenerDetector.get(chain.thisObject), event.downTime,
                        enabled(), SafeModeRuntime.blocked)) true
                else chain.proceed()
            }) return 1
        val trackers = WeakHashMap<View, Binding>()
        return if (HookRuntime.hook(methods.getValue(t.touch), "$FEATURE/nativeTouch") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            val event = chain.args[0] as? MotionEvent ?: return@hook chain.proceed()
            if (view.javaClass.name != t.VIEW || Looper.myLooper() != Looper.getMainLooper())
                return@hook chain.proceed()
            // 分发前提取样本；原生事件只在同步调用期间作身份核对，不保存或回收宿主事件。
            val sample = Sample(event.actionMasked, event.eventTime, event.downTime, event.x, event.y,
                event.pointerCount, event.buttonState,
                event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,
                event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0,
                if (event.historySize <= 64) List(event.historySize) { event.getHistoricalX(it) to event.getHistoricalY(it) } else null)
            val selected = selection()
            val owner = gate.detector.get(view)
            val reserveDoubleTap = runCatching {
                sample.history != null && usable(view, selected) && gate.inRegion(view, sample.x, sample.y) &&
                    StatusBarGesturePolicy.eligible(enabled(), SafeModeRuntime.blocked,
                        true, view.isAttachedToWindow, view.isShown && view.windowVisibility == View.VISIBLE,
                        view.display?.displayId == 0,
                        view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != false,
                        sample.finger, sample.pointers, sample.buttons, sample.obscured,
                        sample.x, sample.y, view.width, view.height)
            }.getOrDefault(false)
            // 在 DOWN 就认领，保证原生 TAP 消息早于第二次抬手时也不能抢先滚动。
            if (reserveDoubleTap && sample.action == MotionEvent.ACTION_DOWN)
                doubleTapScope.reserveTap(owner, sample.downTime)
            val original = try {
                doubleTapScope.during(owner, event, reserveDoubleTap) { chain.proceed() }
            } catch (failure: Throwable) {
                trackers[view]?.cancel()
                throw failure
            }
            val allowed = runCatching {
                reserveDoubleTap && usable(view, selected) &&
                    StatusBarGesturePolicy.eligible(enabled(), SafeModeRuntime.blocked,
                        original == true, view.isAttachedToWindow, view.isShown && view.windowVisibility == View.VISIBLE,
                        view.display?.displayId == 0,
                        view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != false,
                        sample.finger, sample.pointers, sample.buttons, sample.obscured,
                        sample.x, sample.y, view.width, view.height)
            }.getOrDefault(false)
            if (!allowed) {
                trackers.remove(view)?.let { it.cancel(); view.removeOnAttachStateChangeListener(it) }
                return@hook original
            }
            var binding = trackers[view]
            if (binding != null && (binding.configuration != view.resources.configuration || binding.selected != selected)) {
                binding.cancel(); view.removeOnAttachStateChangeListener(binding); binding = null
            }
            val weakView = WeakReference(view)
            val current = binding ?: Binding(view, selected, main,
                valid = { weakView.get()?.let { usable(it, selected) &&
                    it.resources.configuration == trackers[it]?.configuration } == true },
                emit = { trigger ->
                    weakView.get()?.let { target ->
                        if (usable(target, selected)) {
                            if (trigger.double) doubleTapScope.consumePair(
                                gate.detector.get(target), trigger.firstDown, trigger.downTime)
                            val action = if (trigger.double) selected.double else selected.single
                            actions.run(action, target.context.applicationContext,
                                stillAllowed = { weakView.get()?.let { usable(it, selected) &&
                                    it.resources.configuration == trackers[it]?.configuration } == true },
                                native = { gate.native(target, trigger.double) })
                        }
                    }
                }).also { trackers[view] = it; view.addOnAttachStateChangeListener(it) }
            val tracker = current.tracker
            val trigger = when (sample.action) {
                MotionEvent.ACTION_DOWN -> tracker.down(sample.time, sample.downTime, sample.x, sample.y)
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    for ((x, y) in sample.history.orEmpty()) {
                        if (!x.isFinite() || !y.isFinite()) tracker.reset() else tracker.move(x, y)
                    }
                    if (sample.action == MotionEvent.ACTION_MOVE) { tracker.move(sample.x, sample.y); null }
                    else tracker.up(sample.time, sample.downTime, sample.x, sample.y)
                }
                else -> { current.cancel(); null }
            }
            current.schedule()
            if (trigger != null) current.emit(trigger)
            original
        }) 3 else 2
    }

    private data class Sample(val action: Int, val time: Long, val downTime: Long, val x: Float, val y: Float,
        val pointers: Int, val buttons: Int, val finger: Boolean, val obscured: Boolean,
        val history: List<Pair<Float, Float>>?)

    private class Binding(view: View, val selected: Selection, private val main: Handler,
        private val valid: () -> Boolean, val emit: (StatusBarTapTracker.Trigger) -> Unit) : View.OnAttachStateChangeListener {
        val configuration = Configuration(view.resources.configuration)
        val tracker = ViewConfiguration.get(view.context).let {
            StatusBarTapTracker(it.scaledDoubleTapSlop.toFloat(), it.scaledTouchSlop.toFloat(),
                ViewConfiguration.getDoubleTapTimeout().toLong())
        }
        private val confirm = Runnable {
            if (!valid()) cancel() else tracker.expire(SystemClock.uptimeMillis())?.let(emit)
        }
        fun schedule() {
            main.removeCallbacks(confirm)
            tracker.deadline?.let { main.postAtTime(confirm, it) }
        }
        fun cancel() { main.removeCallbacks(confirm); tracker.reset() }
        override fun onViewAttachedToWindow(v: View) { cancel() }
        override fun onViewDetachedFromWindow(v: View) { cancel() }
    }
}
