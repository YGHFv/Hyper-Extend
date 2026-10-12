/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object StatusBarGesturePolicy {
    fun eligible(enabled: Boolean, blocked: Boolean, accepted: Boolean, attached: Boolean,
        shown: Boolean, mainDisplay: Boolean, exploring: Boolean, finger: Boolean,
        pointers: Int, buttons: Int, obscured: Boolean, x: Float, y: Float, width: Int, height: Int): Boolean =
        enabled && !blocked && accepted && attached && shown && mainDisplay && !exploring && finger &&
            pointers == 1 && buttons == 0 && !obscured && width > 0 && height > 0 &&
            x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height
}
