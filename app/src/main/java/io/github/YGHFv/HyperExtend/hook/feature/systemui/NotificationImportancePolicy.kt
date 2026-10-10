/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationImportancePolicy {
    // Unknown/unspecified rankings must not make an existing notification disappear.
    fun counted(importance: Int?): Boolean = importance == null || importance !in 0..1
    fun selection(raw: Any?): Int? = (raw as? String)?.toIntOrNull()?.takeIf { it in 1..4 }
    // Other hidden rows have no live binding on Settings 17; do not expose inert controls.
    fun expose(key: String?): Boolean = key == "importance"
}
