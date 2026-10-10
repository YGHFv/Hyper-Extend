/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

// Historical policy fixture only; runtime ownership lives in VolumeFooterHooks.
internal fun collapsedFooterVisible(hostRequested: Boolean, expanded: Boolean, appPanel: Boolean = false): Boolean =
    hostRequested && expanded && !appPanel
