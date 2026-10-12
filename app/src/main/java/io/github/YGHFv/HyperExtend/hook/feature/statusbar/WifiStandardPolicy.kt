/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object WifiStandardPolicy {
    fun recover(version: Long?, mode: Int, mapperId: Int, raw: Int, filtered: Int): Int? =
        raw.takeIf { version == 202602260L && mode == 1 && mapperId == 2 &&
            raw in 4..5 && filtered == 0 }
}
