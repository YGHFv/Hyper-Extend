/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object ScreenshotEditorSettings {
    const val DEVICE_FEATURE = "screenshot_device_customize"
    const val DEVICE = "$DEVICE_FEATURE.device"
    const val PRIVACY_FEATURE = "screenshot_unlock_privacy_marking"

    private val DEVICE_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")

    /** 外壳资源键只允许宿主现有资源表能表示的标识符，空白表示保持原生。 */
    fun device(raw: String): String? = raw.trim().takeIf(DEVICE_PATTERN::matches)

    val config = listOf(
        HyperText(
            DEVICE,
            "设备代号",
            summary = "填写截图外壳资源使用的设备标识；留空跟随系统。仅改变截图编辑器查询，不改 Build.DEVICE。",
            placeholder = "例如 shell_code",
        ),
    )
}
