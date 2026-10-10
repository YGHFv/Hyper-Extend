/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.SafeModeIncident
import io.github.YGHFv.HyperExtend.core.SafeModeStore
import io.github.YGHFv.HyperExtend.core.SCOPES
import java.io.File
import java.util.UUID

/** Never launches an Activity in a broken host. Persist first, report asynchronously. */
internal object SafeModeRuntime {
    @Volatile var blocked = false
        private set
    private var scope: String? = null
    private var resetToken = 0L
    private var store: SafeModeStore? = null
    private var incident: SafeModeIncident? = null

    @Synchronized fun begin(process: String, dataDir: String?, settings: HookSettings): Boolean {
        // Multiple package-ready callbacks must never lift a process-local trip.
        if (scope != null) return blocked
        val target = SCOPES.firstOrNull { it.process == process } ?: return false
        scope = target.id
        resetToken = settings.safeModeReset(target.id)
        store = if (process == "system") SafeModeStore(File("/data/system/hyperextend_safe_mode"))
            else dataDir?.let { SafeModeStore(File(it, "files/hyperextend_safe_mode")) }
        val state = store?.read() ?: Result.failure(IllegalStateException("missing data directory"))
        val old = state.getOrNull()
        if (state.isFailure) {
            trip("安全状态文件无法读取，已暂停模块功能")
            return true
        }
        if (old != null && resetToken > old.resetToken) {
            if (store?.clear() != true) {
                trip("无法清除安全状态，已继续停用模块功能")
                return true
            }
        } else if (old != null) {
            blocked = true
            incident = old
            report(target.id, old)
            return true
        }
        blocked = settings.safeModeDisabled(target.id)
        return blocked
    }

    @Synchronized fun trip(reason: String) {
        val target = scope ?: return
        blocked = true
        if (incident != null) return
        val event = SafeModeIncident(UUID.randomUUID().toString(), resetToken, System.currentTimeMillis().coerceAtLeast(0), reason.take(240))
        incident = event
        val persisted = store?.write(event) == true
        ModuleLog.warn("SAFE MODE [$target]: ${event.reason}; persisted=$persisted; restart required for full cleanup")
        report(target, event)
    }

    private fun report(target: String, event: SafeModeIncident) {
        runCatching { Thread({
            // Early boot, locked credential storage or a stopped module can postpone delivery.
            // One sleeping daemon per incident retries without ever launching an Activity.
            var attempt = 0
            while (!Thread.currentThread().isInterrupted) {
                val sent = runCatching {
                    val activityThread = Class.forName("android.app.ActivityThread")
                    val app = activityThread.getDeclaredMethod("currentApplication").invoke(null) as? Context
                    val context = app ?: run {
                        val thread = activityThread.getDeclaredMethod("currentActivityThread").invoke(null)
                        activityThread.getDeclaredMethod("getSystemContext").invoke(thread) as? Context
                    }
                    // Any reply means delivered, including a stale report rejected after recovery.
                    context?.contentResolver?.call(Uri.parse("content://io.github.YGHFv.HyperExtend.safety"), "report", target,
                        Bundle().apply { putString("incident", event.encode()) }) != null
                }.getOrDefault(false)
                if (sent) return@Thread
                attempt = (attempt + 1).coerceAtMost(15)
                try { Thread.sleep(attempt * 2000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "HyperExtend-safety-report").apply { isDaemon = true }.start() }
            .onFailure { ModuleLog.error("safe mode report unavailable; marker retained", it) }
    }
}
