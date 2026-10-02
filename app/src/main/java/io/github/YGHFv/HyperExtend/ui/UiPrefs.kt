/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.ui

import android.content.Context
import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.core.LauncherIconStyle

/**
 * **模块界面自身**的外观偏好。
 *
 * ## 为什么单开一份存储，不塞进 `HyperSettings`
 *
 * `HyperSettings` 那份是「功能的开关」，它会被投影给被注入的进程 —— 注入侧读它、
 * 依赖它。这一份（主题、底栏样式）只描述**模块这个界面长什么样**，被注入的宿主进程
 * 一个字都不需要知道。两者混在一起的后果是：改一次界面主题会触发一次无意义的跨进程投影，
 * 而且 `HyperSettings` 的白名单（`STRING_KEYS` / `FEATURES`）也不再是「功能清单」了。
 *
 * ## 为什么不复用系统的深浅色开关
 *
 * 模块界面一年打开不了几次，但它对「看得清」这件事很敏感：很多人系统是浅色、
 * 却希望这个纯工具界面用深色。给一个显式的三档（跟随系统 / 浅色 / 深色）比
 * 强迫用户去改系统主题再回来要合理得多。
 */
object UiPrefs {

    private const val NAME = "hyper_extend_ui"

    const val THEME_FOLLOW_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val KEY_THEME = "theme_mode"
    private const val KEY_BLUR = "blur_bars"
    private const val KEY_FLOATING = "floating_bar"
    private const val KEY_GLASS = "liquid_glass"
    private const val KEY_HIDE_RECENT = "hide_recent_task"
    private const val KEY_HIDE_LAUNCHER = "hide_launcher_icon"
    private const val KEY_ICON_STYLE = "icon_style"

    fun logLevel(context: Context): String = prefs(context).getString("log_level", "DEBUG")
        ?.takeIf { value -> io.github.YGHFv.HyperExtend.core.LogLevel.entries.any { it.name == value } } ?: "DEBUG"

    fun logCards(context: Context): Boolean = prefs(context).getBoolean("log_cards", true)

    fun logReversed(context: Context): Boolean = prefs(context).getBoolean("log_reversed", false)

    fun setLogDisplay(context: Context, level: String, cards: Boolean, reversed: Boolean) {
        prefs(context).edit().putString("log_level", level).putBoolean("log_cards", cards)
            .putBoolean("log_reversed", reversed).apply()
    }

    /** 界面外观与个性化的选项快照。界面按这一份渲染，改完立刻替换。 */
    data class UiState(
        val themeMode: Int = THEME_FOLLOW_SYSTEM,
        /** 顶栏 / 底栏是否用毛玻璃背景。 */
        val blurBars: Boolean = false,
        /** 底栏是否改成悬浮胶囊（澎湃水底栏）。 */
        val floatingBar: Boolean = false,
        /** 悬浮底栏是否再叠一层液态玻璃（只在 [floatingBar] 为真时有意义）。 */
        val liquidGlass: Boolean = false,
        /** 在最近任务里隐藏本应用。 */
        val hideRecentTask: Boolean = false,
        /** 在桌面隐藏应用图标（全部 launcher 别名禁用）。 */
        val hideLauncherIcon: Boolean = false,
        /** 当前选中的桌面图标配色（[LauncherIconStyle.key]）。 */
        val iconStyle: String = LauncherIconStyle.DEFAULT.key,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun read(context: Context): UiState {
        val prefs = prefs(context)
        return UiState(
            themeMode = prefs.getInt(KEY_THEME, THEME_FOLLOW_SYSTEM),
            blurBars = prefs.getBoolean(KEY_BLUR, false),
            floatingBar = prefs.getBoolean(KEY_FLOATING, false),
            liquidGlass = prefs.getBoolean(KEY_GLASS, false),
            hideRecentTask = prefs.getBoolean(KEY_HIDE_RECENT, false),
            hideLauncherIcon = prefs.getBoolean(KEY_HIDE_LAUNCHER, false),
            iconStyle = prefs.getString(KEY_ICON_STYLE, null) ?: LauncherIconStyle.DEFAULT.key,
        )
    }

    fun themeMode(context: Context): Int = prefs(context).getInt(KEY_THEME, THEME_FOLLOW_SYSTEM)

    fun setThemeMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_THEME, mode.coerceIn(THEME_FOLLOW_SYSTEM, THEME_DARK)).apply()
    }

    /** 把 [state] 里「个性化」相关的三项落库。图标配色与隐藏互斥的细节在 [LauncherIcons]。 */
    fun writePersonalization(context: Context, state: UiState) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_RECENT, state.hideRecentTask)
            .putBoolean(KEY_HIDE_LAUNCHER, state.hideLauncherIcon)
            .putString(KEY_ICON_STYLE, state.iconStyle)
            .apply()
    }

    fun hideRecentTask(context: Context): Boolean = prefs(context).getBoolean(KEY_HIDE_RECENT, false)

    fun setBlurBars(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLUR, enabled).apply()
    }

    fun setFloatingBar(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLOATING, enabled).apply()
    }

    fun setLiquidGlass(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GLASS, enabled).apply()
    }
}
