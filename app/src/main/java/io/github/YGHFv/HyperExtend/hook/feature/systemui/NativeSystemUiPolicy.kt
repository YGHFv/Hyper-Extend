/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.NativeSystemUiSettings

internal object NativeSystemUiPolicy {
    fun plugin(feature: String): String? = when (feature) {
        NativeSystemUiSettings.VOLUME -> "com.android.systemui.plugins.VolumeDialog"
        NativeSystemUiSettings.POWER -> "com.android.systemui.plugins.GlobalActions"
        else -> null
    }

    fun eligible(enabled: Boolean, display: Int?, mainThread: Boolean) =
        enabled && display == 0 && mainThread
}

/** A registration scope cannot escape to another thread or survive a throwing constructor. */
internal class NativeExtensionScope {
    private val feature = ThreadLocal<String?>()
    fun matches(plugin: String) = NativeSystemUiPolicy.plugin(feature.get().orEmpty()) == plugin
    fun <T> within(id: String, block: () -> T): T {
        val previous = feature.get()
        feature.set(id)
        return try { block() } finally {
            if (previous == null) feature.remove() else feature.set(previous)
        }
    }
}
