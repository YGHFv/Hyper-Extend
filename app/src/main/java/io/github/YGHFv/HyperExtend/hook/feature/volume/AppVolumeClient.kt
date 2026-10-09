/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/** One in-flight write, coalesced by UID. Late acknowledgements cannot revive a closed panel. */
internal class AppVolumeClient(
    private val context: Context,
    private val token: String,
    private val snapshot: (AppVolumeBridge.Snapshot) -> Unit,
    private val failed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val replies = Handler(Looper.getMainLooper())
    private val pending = linkedMapOf<Int, Int>()
    private var sequence = 0L
    private var closed = false
    private var closing = false
    private var writing = false
    private var flushScheduled = false
    private val flush = Runnable { flushScheduled = false; flush() }
    private val refresh = Runnable { query() }

    fun start() { main.postDelayed(refresh, 2000) }

    fun write(uid: Int, level: Int, immediate: Boolean = false) {
        if (closed || closing || !AppVolumeBridgePolicy.levelValid(level)) return
        pending[uid] = level
        if (immediate) flush() else if (!flushScheduled) {
            flushScheduled = true
            main.postDelayed(flush, 40)
        }
    }

    private fun flush() {
        if (closed || writing) return
        val next = pending.entries.firstOrNull() ?: run { if (closing) finish(); return }
        val uid = next.key
        val level = next.value
        pending.remove(uid)
        writing = true
        request(writeIntent(uid, level)) { _, _ -> writing = false; flush() }
    }

    private fun writeIntent(uid: Int, level: Int) = AppVolumeBridge.request(AppVolumeBridge.WRITE, token)
        .putExtra("uid", uid).putExtra("level", level).putExtra("sequence", ++sequence)

    private fun query() {
        if (closed || closing) return
        request(AppVolumeBridge.request(AppVolumeBridge.QUERY, token)) { _, extras ->
            val value = AppVolumeBridge.decode(extras)
            if (value == null || value.token != token) fail() else {
                runCatching { snapshot(value) }.onFailure { fail() }
                if (!closed && !closing) main.postDelayed(refresh, 2000)
            }
        }
    }

    private fun request(intent: Intent, done: (Int, Bundle?) -> Unit) {
        var answered = false
        val timeout = Runnable {
            if (!answered && !closed && !(closing && intent.action == AppVolumeBridge.QUERY)) { answered = true; fail() }
        }
        main.postDelayed(timeout, 2000)
        runCatching {
            context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (answered || closed) return
                    answered = true
                    main.removeCallbacks(timeout)
                    if (closing && intent.action == AppVolumeBridge.QUERY) return
                    val extras = getResultExtras(false)
                    if (resultCode != Activity.RESULT_OK || extras?.getString(AppVolumeBridge.TOKEN) != token) fail()
                    else done(resultCode, extras)
                }
            }, replies, Activity.RESULT_CANCELED, null, null)
        }.onFailure { main.removeCallbacks(timeout); fail() }
    }

    private fun fail() {
        if (closed) return
        val notify = !closing
        finish()
        if (notify) failed()
    }

    fun close(flushPending: Boolean = true) {
        if (closed || closing) return
        closing = true
        main.removeCallbacksAndMessages(null)
        if (!flushPending) { finish(); return }
        // Acknowledge the final drag before END instead of relying on cross-action broadcast order.
        main.postDelayed({ finish() }, 2000)
        flush()
    }

    private fun finish() {
        if (closed) return
        closed = true
        main.removeCallbacksAndMessages(null)
        pending.clear()
        end(context, token)
    }

    companion object {
        fun end(context: Context, token: String) {
            runCatching { context.sendOrderedBroadcast(AppVolumeBridge.request(AppVolumeBridge.END, token), null) }
        }
    }
}
