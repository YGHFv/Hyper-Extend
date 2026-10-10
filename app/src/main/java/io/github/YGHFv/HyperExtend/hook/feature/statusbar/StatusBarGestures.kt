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

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import io.github.YGHFv.HyperExtend.hook.feature.systemui.DoubleTapTracker

/** 「双击状态栏锁屏」。作用域：com.android.systemui。 */
internal object StatusBarGestures {

    private const val FEATURE = "status_bar_double_tap"
    private const val STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val view = Reflect.loadClass(loader, STATUS_BAR_VIEW) ?: run {
            ModuleLog.warn("$FEATURE: $STATUS_BAR_VIEW not found")
            return 0
        }
        val inflate = Reflect.findMethod(view, "onFinishInflate") ?: run {
            ModuleLog.warn("$FEATURE: onFinishInflate not found")
            return 0
        }
        val ok = HookRuntime.hookAfter(inflate, "$FEATURE/MiuiPhoneStatusBarView#onFinishInflate") { chain, original ->
            attach(chain.thisObject)
            original
        }
        return if (ok) 1 else 0
    }

    /** Observe completed taps without consuming the native shade gesture. */
    private fun attach(target: Any?) {
        val view = target as? View ?: return
        if (view.getTag(TAG_MARKER) != null) return
        view.setTag(TAG_MARKER, true)

        val configuration = ViewConfiguration.get(view.context)
        val tracker = DoubleTapTracker(configuration.scaledDoubleTapSlop.toFloat(),
            ViewConfiguration.getDoubleTapTimeout().toLong(), configuration.scaledTouchSlop.toFloat())
        val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { tracker.reset() }
            override fun onViewDetachedFromWindow(view: View) { tracker.reset() }
        })
        view.setOnTouchListener { touched, event ->
            if (SafeModeRuntime.blocked || accessibility?.isTouchExplorationEnabled == true || event.pointerCount != 1) {
                tracker.reset()
            } else when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> tracker.down(event.eventTime, event.x, event.y)
                MotionEvent.ACTION_MOVE -> tracker.move(event.x, event.y)
                MotionEvent.ACTION_UP -> if (tracker.up(event.eventTime, event.x, event.y)) goToSleep(touched.context)
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> tracker.reset()
            }
            false
        }
    }

    /**
     * 息屏。
     *
     * `PowerManager#goToSleep` 是隐藏 API（`compileOnly` 的 android.jar 里没有），
     * 只能反射调用 —— 与参考项目同一条路。失败只记日志：这一下没息屏，用户会再按电源键，
     * 不该因此让整个状态栏崩掉。
     */
    private fun goToSleep(context: Context) {
        val manager = Reflect.attempt {
            context.getSystemService(Context.POWER_SERVICE)
        } ?: return
        // 只有一个参数的那一个重载才是 `goToSleep(long)`；带 reason 的重载是另一个签名。
        val method = Reflect.firstMethod(manager.javaClass, "goToSleep") { it.parameterCount == 1 }
        if (method == null) {
            ModuleLog.warn("$FEATURE: goToSleep unavailable on this build")
            return
        }
        Reflect.attempt { method.invoke(manager, SystemClock.uptimeMillis()) }
    }

    /** 视图标记：同一个状态栏视图只挂一次（`onFinishInflate` 可能被走到多次）。 */
    private const val TAG_MARKER = 0x7e480102
}
