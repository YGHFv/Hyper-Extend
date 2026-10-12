/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object ForceBlurPolicy {
    fun supported(enabled: Boolean, blocked: Boolean, version: Long?, hardware: Boolean,
        disabledProperty: Boolean, crossWindow: Boolean): Boolean =
        enabled && !blocked && version == 202602260L && hardware && !disabledProperty && crossWindow

    fun owns(expectedRadius: Int, actualRadius: Int, sameRoot: Boolean): Boolean =
        sameRoot && expectedRadius > 0 && expectedRadius == actualRadius

    // Cleanup is the sole path that can ignore capability gates, and can never
    // request a positive radius or start a new early-wakeup transaction.
    fun radius(requested: Int, cleanup: Boolean): Int = if (cleanup) 0 else requested
}
