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
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.res.Configuration
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
import io.github.YGHFv.HyperExtend.hook.feature.systemui.DoubleTapTracker
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 「双击状态栏锁屏」。作用域：com.android.systemui。 */
internal object StatusBarGestures {

    private const val FEATURE = "status_bar_double_tap"
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = StatusBarGestureTargets
        val methods = (listOf(t.touch, t.dispatch, t.sleep) + t.callers)
            .associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val handler = Class.forName(t.OWNER, false, loader).getDeclaredField("mTouchEventHandler").apply {
            require(type.name == t.HANDLER && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        if (!(listOf(t.touch, t.dispatch) + t.callers).all {
                HookRuntime.deoptimize(methods.getValue(it), "$FEATURE/caller")
            }) return 0
        val trackers = WeakHashMap<View, Binding>()
        return if (HookRuntime.hook(methods.getValue(t.touch), "$FEATURE/nativeTouch") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            val event = chain.args[0] as? MotionEvent ?: return@hook chain.proceed()
            if (view.javaClass.name != t.VIEW || Looper.myLooper() != Looper.getMainLooper())
                return@hook chain.proceed()
            // Snapshot before native dispatch; never keep/recycle the host MotionEvent.
            val sample = Sample(event.actionMasked, event.eventTime, event.x, event.y,
                event.pointerCount, event.buttonState,
                event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER,
                event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0,
                if (event.historySize <= 64) List(event.historySize) { event.getHistoricalX(it) to event.getHistoricalY(it) } else null)
            val original = try { chain.proceed() } catch (failure: Throwable) {
                trackers[view]?.tracker?.reset()
                throw failure
            }
            val allowed = runCatching {
                handler.get(view) != null && sample.history != null &&
                    StatusBarGesturePolicy.eligible(settings.isOn(FEATURE), SafeModeRuntime.blocked,
                        original == true, view.isAttachedToWindow, view.isShown && view.windowVisibility == View.VISIBLE,
                        view.display?.displayId == 0,
                        view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != false,
                        sample.finger, sample.pointers, sample.buttons, sample.obscured,
                        sample.x, sample.y, view.width, view.height)
            }.getOrDefault(false)
            if (!allowed) {
                trackers.remove(view)?.let { it.tracker.reset(); view.removeOnAttachStateChangeListener(it) }
                return@hook original
            }
            var binding = trackers[view]
            if (binding != null && binding.configuration != view.resources.configuration) {
                binding.tracker.reset(); view.removeOnAttachStateChangeListener(binding); binding = null
            }
            val current = binding ?: Binding(view).also { trackers[view] = it; view.addOnAttachStateChangeListener(it) }
            val tracker = current.tracker
            when (sample.action) {
                MotionEvent.ACTION_DOWN -> tracker.down(sample.time, sample.x, sample.y)
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    for ((x, y) in sample.history.orEmpty()) {
                        if (!x.isFinite() || !y.isFinite()) tracker.reset() else tracker.move(x, y)
                    }
                    if (sample.action == MotionEvent.ACTION_MOVE) tracker.move(sample.x, sample.y)
                    else if (tracker.up(sample.time, sample.x, sample.y)) {
                        runCatching {
                            val power = view.context.getSystemService(PowerManager::class.java)
                            if (settings.isOn(FEATURE) && !SafeModeRuntime.blocked &&
                                view.isAttachedToWindow && view.isShown && power?.isInteractive == true &&
                                view.context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == false)
                                methods.getValue(t.sleep).invoke(power, SystemClock.uptimeMillis())
                        }
                    }
                }
                else -> tracker.reset()
            }
            original
        }) 1 else 0
    }

    private data class Sample(val action: Int, val time: Long, val x: Float, val y: Float,
        val pointers: Int, val buttons: Int, val finger: Boolean, val obscured: Boolean,
        val history: List<Pair<Float, Float>>?)

    private class Binding(view: View) : View.OnAttachStateChangeListener {
        val configuration = Configuration(view.resources.configuration)
        val tracker = ViewConfiguration.get(view.context).let {
            DoubleTapTracker(it.scaledDoubleTapSlop.toFloat(), ViewConfiguration.getDoubleTapTimeout().toLong(),
                it.scaledTouchSlop.toFloat())
        }
        override fun onViewAttachedToWindow(v: View) { tracker.reset() }
        override fun onViewDetachedFromWindow(v: View) { tracker.reset() }
    }
}
