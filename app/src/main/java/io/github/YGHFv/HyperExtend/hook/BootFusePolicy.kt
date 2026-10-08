/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import java.io.File
import java.io.FileOutputStream

internal data class BootFuseState(
    val lastStartAt: Long = 0,
    val rapidRestarts: Int = 0,
    val disabled: Boolean = false,
    val lastResetToken: Long = 0,
    val windowStartAt: Long = 0,
) {
    fun encode(): String = "$lastStartAt,$rapidRestarts,${if (disabled) 1 else 0},$lastResetToken,$windowStartAt"

    companion object {
        fun decode(text: String): BootFuseState? {
            val p = text.trim().split(',')
            if (p.size !in 2..5) return null
            val last = p[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val count = p[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
            val disabled = when (p.getOrNull(2)) { null, "0" -> false; "1" -> true; else -> return null }
            val reset = if (p.size < 4) 0 else p[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val window = if (p.size < 5) last else p[4].toLongOrNull()?.takeIf { it in 0..last } ?: return null
            return BootFuseState(last, count, disabled || count >= 3, reset, window)
        }
    }
}

internal object BootFusePolicy {
    const val WINDOW_MS = 5 * 60 * 1000L
    const val MIN_TIME = 1_600_000_000_000L

    /** null means unsafe clock/state: skip hooks without replacing the persisted evidence. */
    fun next(previous: BootFuseState?, now: Long, resetToken: Long, countRestart: Boolean): BootFuseState? {
        if (previous == null || now < MIN_TIME) return null
        if (resetToken > previous.lastResetToken) return BootFuseState(now, 0, false, resetToken, now)
        if (previous.disabled) return previous
        if (now < previous.lastStartAt) return null
        if (!countRestart) return previous
        val sameWindow = previous.lastStartAt > 0 && now - previous.windowStartAt <= WINDOW_MS
        val count = if (sameWindow) (previous.rapidRestarts + 1).coerceAtMost(3) else 0
        return BootFuseState(now, count, count >= 3, previous.lastResetToken,
            if (sameWindow) previous.windowStartAt else now)
    }
}

/** Same-directory replacement prevents a partial/truncated write from silently resetting the fuse. */
internal class BootFuseStore(private val file: File) {
    fun read(): BootFuseState? = runCatching {
        if (!file.exists()) BootFuseState() else BootFuseState.decode(file.readText())
    }.getOrNull()

    fun write(state: BootFuseState): Boolean = runCatching {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        val temp = File(parent, file.name + ".tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(state.encode().toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        temp.renameTo(file)
    }.getOrDefault(false)
}
