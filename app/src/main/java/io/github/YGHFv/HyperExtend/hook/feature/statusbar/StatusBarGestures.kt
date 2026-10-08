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
import kotlin.math.abs

/** 「双击状态栏锁屏」。作用域：com.android.systemui。 */
internal object StatusBarGestures {

    private const val FEATURE = "status_bar_double_tap"
    private const val STATUS_BAR_VIEW = "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView"

    /** 两次点击的间隔上限。与参考项目一致：再慢就不像「双击」了。 */
    private const val DOUBLE_TAP_TIMEOUT_MS = 250L

    /** 两次点击的位移上限（像素）。超过它算两下，不算双击 —— 否则滑动通知栏会误锁屏。 */
    private const val TOUCH_SLOP_PX = 100f

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

    /**
     * 给状态栏视图挂双击监听。
     *
     * 用 `setOnTouchListener` 而不是自己写手势检测：它在**子视图没有消费这次事件**时才会
     * 收到回调（状态栏上的时钟、图标都是子视图），于是「双击空白处锁屏」天然成立，
     * 而点通知图标、下拉通知栏这些操作一点不受影响。
     * 返回 false 也同样是刻意的：事件继续走正常流程，我们只是**顺带**看了一眼。
     */
    private fun attach(target: Any?) {
        val view = target as? View ?: return
        if (view.getTag(TAG_MARKER) != null) return
        view.setTag(TAG_MARKER, true)

        var lastTime = 0L
        var lastX = 0f
        var lastY = 0f
        view.setOnTouchListener { touched, event ->
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            val now = System.currentTimeMillis()
            val doubled = now - lastTime < DOUBLE_TAP_TIMEOUT_MS &&
                abs(event.x - lastX) < TOUCH_SLOP_PX &&
                abs(event.y - lastY) < TOUCH_SLOP_PX
            if (doubled) {
                lastTime = 0L
                goToSleep(touched.context)
            } else {
                lastTime = now
                lastX = event.x
                lastY = event.y
            }
            // 触摸监听的存在会让无障碍服务认为这一行不可点，补一个 click 事件。
            touched.performClick()
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
    private val TAG_MARKER = "hyperextend.double_tap".hashCode()
}
