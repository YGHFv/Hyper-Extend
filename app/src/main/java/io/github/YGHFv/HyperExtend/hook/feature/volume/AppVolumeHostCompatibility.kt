/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.content.Context
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

/** 仅读取宿主版本用于诊断，不把版本号作为功能开关。 */
internal object AppVolumeHostCompatibility {
    private const val PLUGIN = "miui.systemui.plugin"

    data class Versions(val misound: Long?, val plugin: Long?)

    fun versions(context: Context): Versions = Versions(
        runCatching { context.packageManager.getPackageInfo(AppVolumeSettings.PACKAGE, 0).compatibleVersionCode }.getOrNull(),
        runCatching { context.packageManager.getPackageInfo(PLUGIN, 0).compatibleVersionCode }.getOrNull(),
    )
}
