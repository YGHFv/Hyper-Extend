/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object MediaBackgroundSettings {
    const val FEATURE = "media_card_background"
    const val MODE = "$FEATURE.mode"
    const val BLUR = "$FEATURE.blur"
    const val TRANSITION = "$FEATURE.transition"
    val options = listOf(HyperOption(TRANSITION, "封面切换过渡", defaultEnabled = false,
        summary = "普通通知中心封面以 333ms 淡入衔接；遵循系统动画关闭设置。仅背景过渡，不含莫奈前景配色动画"))
    fun mode(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in 0..4 } ?: 0
    val config = listOf(
        HyperChoice(MODE, "背景样式", listOf(
            ChoiceEntry("0", "系统默认"), ChoiceEntry("1", "封面拼色"), ChoiceEntry("2", "模糊封面"),
            ChoiceEntry("3", "径向渐变"), ChoiceEntry("4", "线性渐变"),
        ), summary = "仅普通通知中心卡片；锁屏、AOD 与翻折外屏保留原生。缺少封面时恢复系统背景"),
        HyperSlider(BLUR, "封面模糊强度", 0, 20, 10, unit = " %",
            summary = "仅模糊封面生效；0 为不模糊。使用受限分辨率处理，莫奈配色与环境光尚未迁移"),
    )
}
