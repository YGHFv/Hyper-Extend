/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object MediaBackgroundSettings {
    const val FEATURE = "media_card_background"
    const val MODE = "$FEATURE.mode"
    const val BLUR = "$FEATURE.blur"
    const val TRANSITION = "$FEATURE.transition"
    const val INVERSE = "$FEATURE.inverse"
    const val AMBIENT = "$FEATURE.ambient"
    const val AMBIENT_OPTIMIZED = "$FEATURE.ambient_optimized"
    val options = listOf(
        HyperOption(TRANSITION, "封面与配色切换过渡", defaultEnabled = false,
            summary = "普通通知中心背景与前景配色同步以 333ms 衔接；遵循系统动画关闭设置"),
        HyperOption(INVERSE, "线性渐变自动反色", defaultEnabled = false,
            summary = "仅线性渐变：明亮封面使用深色文字与浅色莫奈背景；锁屏、AOD 不适用"),
        HyperOption(AMBIENT, "系统背景氛围光", defaultEnabled = false,
            summary = "仅系统默认背景；保留原生材质，叠加封面色光晕。播放且实际可见时缓慢运动，可能增加耗电"),
        HyperOption(AMBIENT_OPTIMIZED, "氛围光优化取色", defaultEnabled = false,
            summary = "使用当前宿主的莫奈 CONTENT 色板；关闭时使用有界封面主色采样，不调用共享原生取色缓冲区"),
    )
    fun mode(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in 0..4 } ?: 0
    val config = listOf(
        HyperChoice(MODE, "背景样式", listOf(
            ChoiceEntry("0", "系统默认"), ChoiceEntry("1", "封面拼色"), ChoiceEntry("2", "模糊封面"),
            ChoiceEntry("3", "径向渐变"), ChoiceEntry("4", "线性渐变"),
        ), summary = "仅普通通知中心卡片；锁屏、AOD 与翻折外屏保留原生。缺少封面时恢复系统背景"),
        HyperSlider(BLUR, "封面模糊强度", 0, 20, 10, unit = " %",
            summary = "仅模糊封面生效；0 为不模糊。使用受限分辨率处理及当前宿主莫奈色板"),
    )
}
