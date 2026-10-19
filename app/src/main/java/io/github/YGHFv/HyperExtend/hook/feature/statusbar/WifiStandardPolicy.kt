/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

internal object WifiStandardPolicy {
    @Deprecated("版本号不参与运行期门禁")
    fun recover(version: Long?, mode: Int, mapperId: Int, raw: Int, filtered: Int) =
        recover(mode, mapperId, raw, filtered)
    fun recover(mode: Int, mapperId: Int, raw: Int, filtered: Int): Int? =
        raw.takeIf { mode == 1 && mapperId == 2 &&
            raw in 4..5 && filtered == 0 }
}
