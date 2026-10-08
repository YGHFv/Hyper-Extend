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
 * 功能来源：西米露 / HyperCeiler · HideStatusBarBeforeScreenshot（AGPL-3.0）。
 *
 * 这个功能横跨两个进程，缺一半就只有半边动作：
 *
 *   截屏应用（com.miui.screenshot）      系统界面（com.android.systemui）
 *   ─ 拍照前 发 IsFinished=false ──►     收到 → 把状态栏设为 INVISIBLE
 *   ─ 拍完   发 IsFinished=true  ──►     收到 → 恢复原来的可见性
 *
 * 广播的名字与 extra 名字都照抄参考项目（`miui.intent.TAKE_SCREENSHOT` / `IsFinished`）：
 * 那是两个进程之间唯一的约定，改一个字两边就对不上，而症状是「什么都不发生」。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.view.View
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/** 「截屏时隐藏状态栏」。作用域：com.android.systemui + com.miui.screenshot。 */
internal object ScreenshotStatusBar {

    private const val FEATURE = "status_bar_screenshot_hide"
    private const val ACTION_TAKE_SCREENSHOT = "miui.intent.TAKE_SCREENSHOT"
    private const val EXTRA_IS_FINISHED = "IsFinished"

    /**
     * 发广播与真正取图之间的等待。
     *
     * 广播是**异步**的：不等这一下，SystemUI 常常还没来得及把状态栏藏起来，
     * 截图就已经拍完了 —— 表现为「有时管用有时不管用」。
     * 80ms 是参考项目实测的取值。
     */
    private const val SETTLE_DELAY_MS = 80L

    // ------------------------------------------------------------------ 系统界面侧

    /**
     * 注册接收器。
     *
     * 靶子是状态栏视图自己的 `onAttachedToWindow`：OS4 上原来的
     * `MiuiCollapsedStatusBarFragment` 已经不存在了（状态栏改成了独立的视图树），
     * 而「视图挂到窗口上」这个时刻在任何一版都在，而且拿到的就是那个要隐藏的 View。
     */
    fun installSystemUi(loader: ClassLoader, settings: HookSettings): Int {
        val viewClass = Reflect.loadClass(
            loader,
            "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView",
        ) ?: run {
            ModuleLog.warn("$FEATURE: MiuiPhoneStatusBarView not found")
            return 0
        }
        val attach = Reflect.findMethod(viewClass, "onAttachedToWindow") ?: return 0
        val ok = HookRuntime.hookAfter(attach, "$FEATURE/MiuiPhoneStatusBarView#onAttachedToWindow") { chain, original ->
            register(chain.thisObject)
            original
        }
        return if (ok) 1 else 0
    }

    private fun register(target: Any?) {
        val view = target as? View ?: return
        if (view.getTag(TAG_MARKER) != null) return
        val context = view.context ?: return

        // 只记「进截图前是什么状态」，不去猜 —— 状态栏可能在锁屏、全屏应用里本来就不可见，
        // 直接恢复成 VISIBLE 会把那些场景一起改坏。
        var visibilityBefore = View.VISIBLE
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ACTION_TAKE_SCREENSHOT) return
                val finished = intent.getBooleanExtra(EXTRA_IS_FINISHED, true)
                runCatching {
                    if (finished) {
                        view.visibility = visibilityBefore
                    } else {
                        visibilityBefore = view.visibility
                        view.visibility = View.INVISIBLE
                    }
                }.onFailure { ModuleLog.error("$FEATURE: toggling status bar failed", it) }
            }
        }
        val filter = IntentFilter(ACTION_TAKE_SCREENSHOT)
        val registered = Reflect.attempt {
            // 广播来自另一个应用（截屏），接收器必须是 exported 才能收到。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
        }
        if (registered != null) {
            view.setTag(TAG_MARKER, true)
            ModuleLog.info("$FEATURE: screenshot receiver installed")
        } else {
            ModuleLog.warn("$FEATURE: registerReceiver failed")
        }
    }

    // ------------------------------------------------------------------ 截屏侧

    /**
     * 在截屏应用里，真正取图的前后各发一次广播。
     *
     * 靶子按「方法名 + 第一个参数是 Context」找，而不是写死全限定名：
     * 截屏应用的混淆程度比 SystemUI 高得多，类名换过好几轮，
     * 而 `captureDisplay(Context, int, Rect, String[])` 这个方法签名几版都稳定。
     * 找不到就安静跳过 —— 用户在系统界面那一侧能看到「开关开着、但没有生效」，
     * 比让截屏崩溃好得多。
     */
    fun installScreenshot(loader: ClassLoader, settings: HookSettings): Int {
        val clazz = Reflect.loadClass(loader, *DISPLAY_CAPTURE_CANDIDATES) ?: run {
            ModuleLog.warn("$FEATURE: DisplayCapture not found — sender skipped")
            return 0
        }
        val capture = Reflect.firstMethod(clazz, "captureDisplay") {
            it.parameterCount == 4 && it.parameterTypes.firstOrNull() == Context::class.java
        } ?: run {
            ModuleLog.warn("$FEATURE: ${clazz.simpleName}#captureDisplay(Context,..) not found — sender skipped")
            return 0
        }
        val before = HookRuntime.hook(capture, "$FEATURE/DisplayCapture#captureDisplay(before)") { chain ->
            val context = chain.args.getOrNull(0) as? Context
            if (context != null) {
                send(context, false)
                SystemClock.sleep(SETTLE_DELAY_MS)
            }
            chain.proceed()
        }
        val after = HookRuntime.hookAfter(capture, "$FEATURE/DisplayCapture#captureDisplay(after)") { chain, original ->
            (chain.args.getOrNull(0) as? Context)?.let { send(it, true) }
            original
        }
        return (if (before) 1 else 0) + (if (after) 1 else 0)
    }

    private fun send(context: Context, finished: Boolean) {
        Reflect.attempt {
            context.sendBroadcast(
                Intent(ACTION_TAKE_SCREENSHOT)
                    .putExtra(EXTRA_IS_FINISHED, finished)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            )
        }
    }

    private val TAG_MARKER = "hyperextend.screenshot_hide".hashCode()

    private val DISPLAY_CAPTURE_CANDIDATES = arrayOf(
        "com.miui.screenshot.core.util.DisplayCapture",
        "com.miui.screenshot.util.DisplayCapture",
        "com.miui.screenshot.core.DisplayCapture",
    )
}
