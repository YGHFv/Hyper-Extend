/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.TextView
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.util.WeakHashMap

/** One boundary-aligned task for all attached clocks; no screen-off polling. */
internal class ClockSecondsTicker(private val update: (TextView) -> Unit) {
    private val clocks = WeakHashMap<TextView, Unit>()
    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private val tick = Runnable { refresh() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refresh() }
    }

    fun attach(clock: TextView) {
        clocks[clock] = Unit
        if (context == null) {
            val app = clock.context.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            // These are system-only broadcasts, and registration success is not its sticky return value.
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            context = app
        }
        refresh()
    }

    fun detach(clock: TextView) {
        clocks.remove(clock)
        refresh()
    }

    private fun refresh() {
        handler.removeCallbacks(tick)
        clocks.keys.removeAll { !it.isAttachedToWindow }
        if (clocks.isEmpty() || SafeModeRuntime.blocked) {
            context?.let { Reflect.attempt { it.unregisterReceiver(receiver) } }
            context = null
            return
        }
        val power = context?.getSystemService(PowerManager::class.java) ?: return
        if (!power.isInteractive) return
        clocks.keys.toList().forEach { clock ->
            if (clock.isShown) Reflect.attempt { update(clock) }
        }
        handler.postDelayed(tick, ClockTickPolicy.delay(System.currentTimeMillis()))
    }
}
