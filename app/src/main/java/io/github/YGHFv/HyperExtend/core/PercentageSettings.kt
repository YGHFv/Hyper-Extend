/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object PercentageSettings {
    const val FEATURE = "slider_percentage"
    const val STYLE = "$FEATURE.style"
    const val BRIGHTNESS = "$FEATURE.brightness"
    const val VOLUME = "$FEATURE.volume"
    const val TOP = "$FEATURE.top"
    const val BLUR = "$FEATURE.blur"

    /**
     * v1 的两张独立卡片键（`control_center_brightness_value` / `control_center_volume_value`）。
     *
     * 那两张卡片的效果就是本功能的「原位数值」方式，属于同一个宿主位置，所以已并入这里。
     * 键名留在这里而不是散在迁移代码里：迁移要读它们、旧备份要认它们，
     * 两处各写一份字面量就会在改名时漏掉一处（表现是老用户的开关静默丢失）。
     */
    const val LEGACY_BRIGHTNESS_CARD = "control_center_brightness_value"
    const val LEGACY_VOLUME_CARD = "control_center_volume_value"

    val options = listOf(
        HyperOption(BRIGHTNESS, "显示亮度百分比", defaultEnabled = false,
            summary = "浮层方式覆盖新控制中心、二级亮度、插件亮度窗口与经典亮度；原位方式只复用控制中心原生顶部标签"),
        HyperOption(VOLUME, "显示音量百分比", defaultEnabled = false,
            summary = "浮层方式覆盖控制中心音量与硬件音量面板；原位方式只复用控制中心原生顶部标签"),
        HyperOption(BLUR, "浮层高级模糊", defaultEnabled = false,
            summary = "只作用于模块浮层，保留系统模糊能力和开关；不可用时使用普通半透明背景"),
    )
    val config = listOf(
        HyperChoice(STYLE, "显示方式", listOf(ChoiceEntry("2", "拖动时顶部浮层"), ChoiceEntry("1", "控制中心原位数值")),
            summary = "原位数值复用已适配的原生顶部标签（原「控制中心亮度值 / 音量值」两张卡片的等效方式）；" +
                "浮层覆盖新控制中心和插件亮度窗口。关闭总开关回到原生"),
        HyperSlider(TOP, "浮层顶部距离", 0, 1024, 54, unit = " dp",
            summary = "横屏使用 70% 距离；限制在窗口安全区域，不修改宿主布局或窗口 Insets"),
    )
    fun style(raw: String): Int = if (raw == "1") 1 else 2
    fun top(raw: String): Int = raw.toIntOrNull()?.coerceIn(0, 1024) ?: 54
}
