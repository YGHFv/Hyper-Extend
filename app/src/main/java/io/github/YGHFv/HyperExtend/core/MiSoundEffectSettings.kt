/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.provider.Settings

/** 耳机连接状态由系统框架写入，系统界面与音质音效只读取它。 */
const val MISOUND_EARPHONE_STATE_KEY = "auto_effect_switch_earphone_state"

/** 是否在耳机连接期间锁定音效选择。 */
const val MISOUND_LOCK_SELECTION_KEY = "system_framework_misound_effect.lock_selection"

/** 是否按耳机设备记忆音效。 */
const val MISOUND_REMEMBER_DEVICE_KEY = "system_framework_misound_effect.remember_device"

/** 首次连接且没有设备记忆时应用的默认音效。 */
const val MISOUND_DEFAULT_EFFECT_KEY = "system_framework_misound_effect.default_effect"

const val MISOUND_EFFECT_NONE = "none"
const val MISOUND_EFFECT_DOLBY = "dolby"
const val MISOUND_EFFECT_MISOUND = "misound"
const val MISOUND_EFFECT_KEEP = "keep"

val MISOUND_LOCK_SELECTION_CHOICES = listOf(
    ChoiceEntry("1", "连接耳机时锁定"),
    ChoiceEntry("0", "允许手动切换"),
)

val MISOUND_REMEMBER_DEVICE_CHOICES = listOf(
    ChoiceEntry("0", "不记忆设备"),
    ChoiceEntry("1", "记忆每个设备"),
)

val MISOUND_DEFAULT_EFFECT_CHOICES = listOf(
    ChoiceEntry(MISOUND_EFFECT_NONE, "无音效"),
    ChoiceEntry(MISOUND_EFFECT_DOLBY, "杜比"),
    ChoiceEntry(MISOUND_EFFECT_MISOUND, "小米音效"),
    ChoiceEntry(MISOUND_EFFECT_KEEP, "保持当前音效"),
)

/**
 * 各进程共用的耳机锁定判据。
 *
 * 音效切换与 UI 锁定必须读取同一个状态键；否则会出现磁贴已经锁住、设置页仍能改，
 * 或系统框架已切到默认音效、SystemUI 却显示未连接的情况。
 */
internal fun isMiSoundSelectionLocked(context: Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, MISOUND_EARPHONE_STATE_KEY, 0) == 1
}.getOrDefault(false)
