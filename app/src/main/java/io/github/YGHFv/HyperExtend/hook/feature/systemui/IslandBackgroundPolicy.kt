/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal data class IslandBackgroundOptions(
    val mode: Int = 0, val ambient: Int = 0, val blur: Int = 10,
    val inverse: Boolean = false, val transition: Boolean = false, val optimized: Boolean = false,
) {
    val custom get() = mode in 1..4 || mode == 0 && ambient in 1..2
    val needsArtwork get() = mode in 1..4 || mode == 0 && ambient == 2
}

internal object IslandBackgroundPolicy {
    fun eligible(enabled: Boolean, display: Int?, attached: Boolean,
        shown: Boolean, awake: Boolean, unlocked: Boolean) =
        enabled && display == 0 && attached && shown && awake && unlocked
}

/** Pause time is excluded; two holders use the same clock, including after handoff. */
internal class IslandAmbientClock {
    private var previous: Long? = null
    private var elapsed = 0L
    fun frame(now: Long, running: Boolean): Float {
        previous?.let { if (running) elapsed += (now - it).coerceIn(0L, 100L) }
        previous = if (running) now else null
        return (elapsed % 60_000L) / 60_000f * (2f * kotlin.math.PI.toFloat())
    }
    fun reset() { previous = null; elapsed = 0 }
}
