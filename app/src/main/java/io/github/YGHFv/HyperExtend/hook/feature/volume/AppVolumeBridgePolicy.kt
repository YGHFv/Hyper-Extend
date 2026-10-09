/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

internal object AppVolumeBridgePolicy {
    const val MAX_APPS = 32
    const val MAX_LEVEL = 1500
    const val LEASE_MS = 10000L
    // App ratios are independent of the global stream mute state. Never unmute the stream here.
    fun unavailableReason(locked: Boolean, interactive: Boolean, disabled: Boolean): String? = when {
        locked -> "locked"
        !interactive -> "screen_off"
        disabled -> "disabled"
        else -> null
    }
    fun hideNativeBall(ready: Boolean, audited: Boolean, isBall: Boolean) = ready && audited && isBall
    fun writable(uid: Int, hostUid: Int, packages: List<String>, packageName: String): Boolean =
        uid >= 0 && uid / 100000 == hostUid / 100000 && uid % 100000 in 10000..19999 &&
            packages.size == 1 && packages.single() == packageName && packageName != "com.miui.miwallpaper"
    fun levelValid(level: Int) = level in 0..MAX_LEVEL
    fun pageCount(count: Int, capacity: Int): Int =
        if (count <= 0) 0 else 1 + (count - 1) / capacity.coerceAtLeast(1)
}

internal class AppVolumeBridgeLease {
    var token: String? = null
        private set
    private var touched = 0L
    private var sequence = -1L
    fun open(token: String, now: Long) { this.token = token; touched = now; sequence = -1 }
    fun accepts(token: String?, now: Long) = this.token != null && this.token == token &&
        now >= touched && now - touched < AppVolumeBridgePolicy.LEASE_MS
    fun touch(now: Long) { touched = now }
    fun write(token: String?, next: Long, now: Long): Boolean {
        if (!accepts(token, now) || next < 0 || next <= sequence) return false
        sequence = next; touched = now
        return true
    }
    fun close() { token = null; sequence = -1 }
}
