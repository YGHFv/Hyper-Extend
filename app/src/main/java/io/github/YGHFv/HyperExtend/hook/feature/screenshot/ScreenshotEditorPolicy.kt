/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

internal object ScreenshotEditorPolicy {
    fun remapShellQuery(query: String, actualDevice: String, customDevice: String?): String {
        val custom = customDevice?.trim().orEmpty()
        if (custom.isEmpty() || actualDevice.isEmpty() || custom == actualDevice) return query
        if (query == actualDevice) return custom
        val prefix = "${actualDevice}_"
        return if (query.startsWith(prefix)) custom + query.removePrefix(actualDevice) else query
    }
}
