/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/** 动作编号参考 XiaomiHelper 的 CommonGesture；保留旧主键，避免升级后丢失双击息屏开关。 */
object StatusBarGestureSettings {
    const val FEATURE = "status_bar_double_tap"
    const val SINGLE = "$FEATURE.single_action"
    const val DOUBLE = "$FEATURE.double_action"

    enum class Action(val value: String, val title: String) {
        DEFAULT("0", "跟随系统"),
        DISABLED("11", "禁用"),
        NOTIFICATIONS("1", "通知面板"),
        QUICK_SETTINGS("2", "控制中心"),
        SLEEP("3", "息屏"),
        SCREENSHOT("4", "截屏"),
        BACK("12", "返回"),
        HOME("5", "回到桌面"),
        RECENTS("6", "最近任务"),
        RECOGNIZE_SCREEN("7", "小爱识屏"),
        VOICE_ASSIST("8", "唤醒小爱"),
        FLOATING_WINDOW("9", "当前应用小窗"),
        SCROLL_TOP("10", "回到页面顶部"),
    }

    data class Selection(val single: Action, val double: Action) {
        val custom: Boolean get() = single != Action.DEFAULT || double != Action.DEFAULT
    }

    fun selection(single: String, double: String): Selection = Selection(
        parse(single, Action.DEFAULT), parse(double, Action.SLEEP),
    )

    private fun parse(value: String, fallback: Action): Action =
        if (value.isBlank()) fallback else Action.entries.firstOrNull { it.value == value } ?: Action.DEFAULT

    private fun choices(first: Action): List<ChoiceEntry> =
        (listOf(first) + Action.entries.filter { it != first }).map { ChoiceEntry(it.value, it.title) }

    val config: List<HyperConfigRow> = listOf(
        HyperChoice(SINGLE, "单击动作", choices(Action.DEFAULT),
            summary = "等待双击判定结束后执行；双击不会先执行单击动作"),
        HyperChoice(DOUBLE, "双击动作", choices(Action.SLEEP),
            summary = "第二次有效抬手时执行一次，不同时触发系统原生滚动"),
    )
}
