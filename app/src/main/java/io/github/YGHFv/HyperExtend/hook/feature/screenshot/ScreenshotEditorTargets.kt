/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.screenshot

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

/** 当前 com.miui.screenshot 宿主的精确成员，证据见同名缓存成员资源。 */
internal object ScreenshotEditorTargets {
    const val SHELL_FETCHER = "com.miui.gallery.editor.photo.screen.shell.res.ShellResourceFetcher"
    const val MOSAIC_VIEW = "com.miui.gallery.editor.photo.screen.mosaic.ScreenMosaicView"

    val shellEntry = SystemUiMethod(SHELL_FETCHER, "getResId", "long", isStatic = true)
    val shellQuery = SystemUiMethod(
        SHELL_FETCHER,
        "getResId",
        "long",
        listOf("java.lang.String"),
        isStatic = true,
    )
    val privacyMarking = SystemUiMethod(MOSAIC_VIEW, "isSupportPrivacyMarking", "boolean", isStatic = true)

    val all = listOf(shellEntry, shellQuery, privacyMarking)
}
