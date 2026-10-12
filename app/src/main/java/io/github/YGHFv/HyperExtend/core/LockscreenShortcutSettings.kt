/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 锁屏**底部左侧按钮**的处理方式 —— 「隐藏左下角按钮」与「左侧入口替换为手电筒」两卡合并落点。
 *
 * ## 为什么合并
 *
 * 上游本来就是**一个下拉框**：`prefs_key_system_ui_lock_screen_bottom_left_button`，
 * 取值 `0=默认 / 1=隐藏 / 2=手电筒`。迁移时被拆成了两张独立开关卡片
 * （`lockscreen_hide_left_shortcut` 与 `lockscreen_left_flashlight`），于是两者可以同时打开，
 * 而它们抢的是**同一个视图**，只能靠代码里写死的「隐藏优先」来收场：
 * 用户看到的是「两个开关都开着，但只有一个生效」，且不生效的那个既没提示也无法解释。
 *
 * 合并回上游那一个三选一之后，「同时开启」这个状态在数据层面就不存在了，
 * 也就不需要那条隐藏的优先规则。右侧按钮（`lockscreen_hide_right_shortcut`）作用在**另一个位置**，
 * 与左侧不是同一件事，保持独立卡片不动。
 *
 * ## 键名与迁移
 *
 * 卡片主键保留 `lockscreen_hide_left_shortcut`（就是老「隐藏」卡片的键），
 * 于是**不需要给这张卡片改名**：只有「手电筒」那张卡的顶层键需要删掉，
 * 它在 schema v5->v6 里被读成 [MODE] 的一个取值后移除（见 [MergedCardMigration.leftButtonMergePlan]）。
 *
 * 迁移**保留用户当时实际看到的画面**：老的两张卡都开着时，隐藏优先是事实行为，
 * 所以优先搬成「隐藏」而不是「手电筒」。
 *
 * ## 与「默认」档的关系
 *
 * `MODE_DEFAULT` 与「卡片关着」在效果上等价（都不改左侧按钮），但它是**显式**的取值：
 * 上游那个下拉框的默认项就是它。保留它可以让用户在「我只是想先关掉处理」时
 * 不必去动总开关，也让老设置里的 `0` 有一个明确的落点。
 */
object LockscreenShortcutSettings {
    /** 合并后的卡片主键，刻意等于老「隐藏左侧入口」卡片的键。 */
    const val FEATURE = "lockscreen_hide_left_shortcut"

    const val MODE = "$FEATURE.mode"
    const val MODE_DEFAULT = "0"
    const val MODE_HIDE = "1"
    const val MODE_FLASHLIGHT = "2"

    /** 合并前「左侧入口替换为手电筒」那张独立卡片的顶层键；迁移时读它，之后删除。 */
    const val LEGACY_FLASHLIGHT_CARD = "lockscreen_left_flashlight"

    val mode = HyperChoice(
        MODE, "处理方式",
        listOf(
            ChoiceEntry(MODE_DEFAULT, "保持原生"),
            ChoiceEntry(MODE_HIDE, "隐藏左侧按钮"),
            ChoiceEntry(MODE_FLASHLIGHT, "左侧按钮改为手电筒"),
        ),
        summary = "隐藏会同时拦下该侧的点击与滑动启动；手电筒为按住 0.4 秒后松开切换，支持无障碍点击。二者作用于同一位置，所以是同一项的两个取值，不能同时选。",
    )
    val config = listOf(mode)

    /** 归一化取值：空串与未知值一律按「保持原生」，与上游下拉框的缺省项一致。 */
    fun modeOf(raw: String): String = when (raw) {
        MODE_HIDE -> MODE_HIDE
        MODE_FLASHLIGHT -> MODE_FLASHLIGHT
        else -> MODE_DEFAULT
    }

    /** 本档是否隐藏左侧按钮（含拦下点击与滑动启动）。 */
    fun hidesLeft(raw: String): Boolean = modeOf(raw) == MODE_HIDE

    /** 本档是否把左侧按钮换成手电筒。 */
    fun replacesLeft(raw: String): Boolean = modeOf(raw) == MODE_FLASHLIGHT
}
