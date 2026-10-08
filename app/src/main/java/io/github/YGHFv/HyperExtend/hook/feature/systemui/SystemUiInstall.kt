/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookSettings

/** A missing/private host API must disable only its own feature, not subsequent hooks. */
internal fun MutableList<String>.installSystemUiFeature(settings: HookSettings, id: String, install: () -> Int) {
    if (!settings.isOn(id)) return
    val count = try {
        install()
    } catch (failure: Throwable) {
        ModuleLog.error("$id: installation failed", failure)
        0
    }
    if (count == 0) ModuleLog.warn("$id: no compatible hooks installed")
    add("$id=$count")
}
