/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationChannelPolicy {
    fun conversation(pkg: String, uid: Int, channel: String, sourcePackage: String?,
        sourceUid: Int?, sourceChannel: String?, shortcut: String?): String? =
        shortcut?.takeIf { it.isNotBlank() && pkg == sourcePackage && uid == sourceUid && channel == sourceChannel }
}
