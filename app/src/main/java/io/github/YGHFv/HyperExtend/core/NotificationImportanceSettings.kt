/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 「解锁通知重要程度」的配置。
 *
 * 本卡片合并了原独立卡片 `notification_channel_settings`：那张卡片做的「长按通知直接跳到
 * 对应渠道」与本卡片的「让渠道重要程度可改」是同一件事的两段（先跳到渠道页，页里才有
 * 可改的重要程度），而且合并前它俩共用同一个菜单拦截器、只会装其中一个 —— 只开重要程度
 * 时菜单跳转也会跟着被改，属于用户没点过的副作用。所以并成一张卡片，把「菜单怎么跳」
 * 变成这里的一个选择项。
 */
object NotificationImportanceSettings {
    const val FEATURE = "notification_importance"

    /**
     * 长按通知菜单里的「通知设置」跳到哪。
     *
     * 默认跟随原生（应用通知设置页），只有用户明确选择才改成直达渠道 ——
     * 合并前只开重要程度会连带改掉跳转目标，现在不会了。
     */
    const val MENU_TARGET = "$FEATURE.menu_target"
    const val TARGET_APP = "1"
    const val TARGET_CHANNEL = "2"

    /** 合并前独立卡片的键；迁移时读它，之后删除。 */
    const val LEGACY_CHANNEL_FEATURE = "notification_channel_settings"

    val config = listOf(
        HyperChoice(MENU_TARGET, "长按菜单的通知设置跳转到",
            listOf(ChoiceEntry(TARGET_APP, "应用通知设置（原生）"), ChoiceEntry(TARGET_CHANNEL, "对应通知渠道")),
            summary = "选「对应通知渠道」时按通知 UID 选择用户并带上同一通知的会话 ID；缺少渠道或无法打开时回退到应用通知设置，保留 Mi Push 特殊入口"),
    )

    fun channelRedirect(raw: String): Boolean = raw == TARGET_CHANNEL
}
