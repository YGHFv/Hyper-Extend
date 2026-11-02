/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * 动作语义参考 XiaomiHelper 的 CommonGesture（GPL-3.0-or-later），按已取证宿主重新实现。
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.StatusBarGestureSettings.Action
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class StatusBarGestureActions(loader: ClassLoader) {
    private val t = StatusBarGestureTargets
    private val sleep = NotificationHooks.resolve(loader, t.sleep)
    private val scroll = NotificationHooks.resolve(loader, t.scrollTop)
    private val input = NotificationHooks.resolve(loader, t.inputInstance)
    private val floating = NotificationHooks.resolve(loader, t.floatingWindow)
    private val main = Handler(Looper.getMainLooper())
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "HyperExtend-状态栏动作").apply { isDaemon = true } })
            .apply { allowCoreThreadTimeOut(true) }
    }

    fun run(action: Action, context: Context, stillAllowed: () -> Boolean, native: () -> Unit) {
        when (action) {
            Action.FLOATING_WINDOW, Action.SCROLL_TOP -> protect(action) {
                main.post {
                    // 视图及其 WeakHashMap 只在主线程检查，后台仅执行已确认的宿主服务调用。
                    protect(action) enqueue@{
                        if (!stillAllowed()) return@enqueue
                        worker.execute {
                            protect(action) execute@{
                                if (!confirmOnMain(stillAllowed)) return@execute
                                if (action == Action.SCROLL_TOP) {
                                    checkNotNull(scroll).invoke(checkNotNull(input).invoke(null))
                                } else {
                                    val message = checkNotNull(floating).invoke(null, context) as? String
                                    if (!message.isNullOrBlank()) main.post {
                                        if (stillAllowed()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            else -> protect(action) {
                if (!stillAllowed()) return@protect
                when (action) {
                    Action.DEFAULT -> native()
                    Action.DISABLED -> Unit
                    Action.SLEEP -> checkNotNull(sleep).invoke(
                        checkNotNull(context.getSystemService(PowerManager::class.java)), SystemClock.uptimeMillis())
                    Action.RECOGNIZE_SCREEN, Action.VOICE_ASSIST -> context.startForegroundService(
                        Intent("android.intent.action.ASSIST").apply {
                            setClassName("com.miui.voiceassist", "com.xiaomi.voiceassistant.VoiceService")
                            putExtra("triggerFrom", "MiuiHome")
                            putExtra("voice_assist_function_key",
                                if (action == Action.RECOGNIZE_SCREEN) "start_screen_recognition" else "wake_up_voice_assist")
                            putExtra("triggerType", if (action == Action.RECOGNIZE_SCREEN) "NavLongPress" else "NavDoubleClick")
                            putExtra("voice_assist_start_from_key",
                                if (action == Action.RECOGNIZE_SCREEN) "long_press_fullscreen_gesture_line"
                                else "double_click_fullscreen_gesture_line")
                        })
                    else -> context.sendBroadcast(Intent(checkNotNull(broadcast(action)))
                        .setPackage("com.android.systemui"))
                }
            }
        }
    }

    private fun protect(action: Action, block: () -> Unit) {
        runCatching(block).onFailure { ModuleLog.error("状态栏动作「${action.title}」执行失败，不补发原生滚动", it) }
    }

    private fun confirmOnMain(check: () -> Boolean): Boolean {
        val allowed = AtomicBoolean(false)
        val done = CountDownLatch(1)
        if (!main.post {
                try { allowed.set(runCatching(check).getOrDefault(false)) } finally { done.countDown() }
            }) return false
        // 不阻塞系统界面；后台超时直接放弃动作，不排队重试。
        return done.await(500, TimeUnit.MILLISECONDS) && allowed.get()
    }

    companion object {
        fun broadcast(action: Action): String? = when (action) {
            Action.NOTIFICATIONS -> "SYSTEM_ACTION_NOTIFICATIONS"
            Action.QUICK_SETTINGS -> "SYSTEM_ACTION_QUICK_SETTINGS"
            Action.SCREENSHOT -> "SYSTEM_ACTION_TAKE_SCREENSHOT"
            Action.BACK -> "SYSTEM_ACTION_BACK"
            Action.HOME -> "SYSTEM_ACTION_HOME"
            Action.RECENTS -> "SYSTEM_ACTION_RECENTS"
            else -> null
        }
    }
}
