/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.config.HyperSettings

internal data class PlannedRestartRequest(val token: Long, val issuedMs: Long, val expiresMs: Long) {
    fun encode(): String = "1,$token,$issuedMs,$expiresMs"
    fun valid(now: Long, consumed: Long): Boolean = token > consumed && token > 0 &&
        issuedMs >= 1_600_000_000_000L && now >= issuedMs && now < expiresMs &&
        expiresMs > issuedMs && expiresMs - issuedMs <= 180_000L

    companion object {
        fun decode(raw: Any?): PlannedRestartRequest? {
            if (raw !is String || raw.length > 96) return null
            val fields = raw.split(',')
            if (fields.size != 4 || fields[0] != "1") return null
            return PlannedRestartRequest(fields[1].toLongOrNull() ?: return null,
                fields[2].toLongOrNull() ?: return null, fields[3].toLongOrNull() ?: return null)
        }
    }
}

/** Java 宿主使用独立请求键；它不是安全模式的恢复令牌，不能解除已有锁存。 */
internal object PlannedRestarts {
    fun key(scope: String) = "safety.$scope.planned_restart"

    @Synchronized fun request(scopes: List<String>, reboot: Boolean = false): Result<Map<String, String>> = runCatching {
        val prefs = checkNotNull(FrameworkBridge.remotePreferences(HyperSettings.GROUP)) { "框架未连接，无法确认主动重启" }
        val now = System.currentTimeMillis()
        require(now >= 1_600_000_000_000L && now <= Long.MAX_VALUE - 180_000L)
        val values = prefs.all
        val requests = scopes.associate { scope ->
            val old = PlannedRestartRequest.decode(values[key(scope)])?.token ?: 0
            require(old < Long.MAX_VALUE)
            key(scope) to PlannedRestartRequest(maxOf(now, old + 1), now,
                now + if (reboot) 180_000L else 30_000L).encode()
        }
        val editor = prefs.edit()
        requests.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "主动重启请求未保存" }
        requests
    }

    @Synchronized fun cancel(requests: Map<String, String>) {
        runCatching {
            val prefs = FrameworkBridge.remotePreferences(HyperSettings.GROUP) ?: return
            val editor = prefs.edit()
            requests.forEach { (key, value) -> if (prefs.all[key] == value) editor.remove(key) }
            editor.commit()
        }
    }
}
