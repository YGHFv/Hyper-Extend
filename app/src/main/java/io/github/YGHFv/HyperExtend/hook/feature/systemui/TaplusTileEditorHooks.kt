/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object TaplusTileEditorHooks {
    private val editor = NativeToggleTileEditorHooks(TaplusTilePolicy.FEATURE, TaplusTilePolicy.SPEC) { TaplusTileHooks.ready }
    fun installClassic(loader: ClassLoader) = editor.installClassic(loader)
    fun installPlugin(loader: ClassLoader) = editor.installPlugin(loader)
}
