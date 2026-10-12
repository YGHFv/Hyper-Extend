/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TaplusTilePolicy {
    const val FEATURE = "control_center_taplus_tile"
    const val SPEC = "hyperextend_taplus"
    const val PACKAGE = "com.miui.contentextension"
    const val VERSION = 50015L
    const val AUTHORITY = "$PACKAGE.provider.switchcontrolprovider"
    const val PROVIDER = "$PACKAGE.provider.SwitchControlProvider"
    const val ACTIVITY = "$PACKAGE.setting.activity.MainSettingsActivity"
    const val PERMISSION = "$PACKAGE.permission.SWITCH_CONTROL"
    const val STATUS = "key_control_status"
    fun argument(enabled: Boolean) = if (enabled) "open" else "close"
    fun append(original: String?) = NativeTogglePolicy.append(original, SPEC)
    fun canAct(enabled: Boolean, safe: Boolean, owner: Boolean, unlocked: Boolean,
        listening: Boolean, age: Long) = NativeTogglePolicy.canAct(enabled, safe, owner, unlocked, listening, age)
}
