/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object LockScreenHintPolicy {
    val types = mapOf(13 to "is_dismissible", 17 to "click_to_unlock_hint", 18 to "key_to_unlock_hint", 19 to "enter_to_unlock_hint")
    fun hide(type: Int, hostName: String?): Boolean = types[type]?.let { it == hostName } == true
}
