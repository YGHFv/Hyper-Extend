/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationAlertPolicy {
    fun mute(muteInteractive: Boolean, interactive: Boolean?, zenFix: Boolean, interruptionFilter: Int?): Boolean =
        (muteInteractive && interactive == true) || (zenFix && interruptionFilter in 2..4)
}
