/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 桌面图标配色与可见性 —— 逻辑参考「阅微补全计划」（reamicro）的实现重写。
 *
 * 每套配色各对应一个 `activity-alias`（见 AndroidManifest.xml），切换即启用目标别名、
 * 禁用其余；「隐藏桌面图标」= 全部禁用。两件事共用同一批组件，所以必须由这里统一管，
 * 各自单独 setComponentEnabledSetting 会互相点亮/漏禁（上游注释里的教训）。
 *
 * ## 顺序即安全
 *
 * 「先启用目标、再禁用其余」：API 26-32 的逐条提交按传入顺序生效，如果先把旧的禁了
 * 而新的还没启用，中间状态里桌面上没有任何 LAUNCHER 入口，部分启动器会直接把图标
 * 从桌面摘掉。API 33+ 走 `setComponentEnabledSettings` 原子提交，不受此影响。
 *
 * ## 状态以组件为准，偏好只是意图
 *
 * 读状态时把 PackageManager 的实际组件状态枚举出来（DEFAULT 按别名在清单里的
 * 默认值算），改完再读一遍核对；失败回滚到上一个稳定选择。这里刻意只保留上游
 * 控制器的「状态核对 + 回滚」主干，去掉崩溃恢复日志（journal）—— 本模块不是
 * 常驻应用，恢复路径在 Application.onCreate 里重放一次选择就够了。
 */

package io.github.YGHFv.HyperExtend.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** 一套桌面图标配色。[background] / [foreground] 是选择器预览用的颜色。 */
enum class LauncherIconStyle(
    /** 落库值。写进偏好文件，不要改。 */
    val key: String,
    /** 设置页展示名。 */
    val label: String,
    /** 别名类名（相对包名）。默认那套沿用历史名字 `.ui.Home`。 */
    val aliasClass: String,
    /** 预览底色（与 adaptive-icon 的 background 一致）。 */
    val background: Long,
    /** 预览前景色（与 adaptive-icon 的前景矢量一致）。 */
    val foreground: Long,
) {
    DEFAULT("default", "经典", ".ui.Home", 0xFF3482FF, 0xFFFFFFFF),
    MONO("mono", "墨白", ".ui.HomeMono", 0xFFF2EFE7, 0xFF1A1A1A),
    GOLD("gold", "鎏金", ".ui.HomeGold", 0xFF2A221E, 0xFFD9B36C),
    NIGHT("night", "暗夜", ".ui.HomeNight", 0xFF12161D, 0xFF93A2FF),
    SKY("sky", "青竹", ".ui.HomeSky", 0xFFDDEEE3, 0xFF2F7D53),
    ;

    companion object {
        /** 找不到就回默认，避免偏好文件被改坏后没有任何图标可用。 */
        fun fromKey(key: String?): LauncherIconStyle =
            entries.firstOrNull { it.key == key } ?: DEFAULT

        fun fromIndex(index: Int): LauncherIconStyle =
            entries.getOrElse(index) { DEFAULT }
    }
}

/** 「桌面图标」状态的快照：当前生效的配色 + 是否隐藏（没有任何启用的别名）。 */
data class LauncherIconState(val style: LauncherIconStyle, val hidden: Boolean)

object LauncherIcons {

    /** 组件状态枚举里，「DEFAULT」在清单里默认启用的那套（历史上只有它）。 */
    private val DEFAULT_ENABLED_KEY = LauncherIconStyle.DEFAULT.key

    private fun component(context: Context, style: LauncherIconStyle) =
        ComponentName(context.packageName, context.packageName + style.aliasClass)

    /**
     * 当前实际生效的选择：启用别名集合（单元素 = 可见，空 = 隐藏，多元素 = 异常）。
     * 任何一个组件查询失败都返回 null —— 状态未知时**不做任何写操作**。
     */
    fun enabledKeys(context: Context): Set<String>? {
        val enabled = mutableSetOf<String>()
        for (style in LauncherIconStyle.entries) {
            val raw = runCatching {
                context.packageManager.getComponentEnabledSetting(component(context, style))
            }.getOrElse { return null }
            val isEnabled = when (raw) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
                -> false

                else -> style.key == DEFAULT_ENABLED_KEY
            }
            if (isEnabled) enabled += style.key
        }
        return enabled
    }

    /** 当前状态（多启用的异常态收敛为「回默认」）。读不到实际状态返回 null。 */
    fun readState(context: Context, preferredKey: String): LauncherIconState? {
        val enabled = enabledKeys(context) ?: return null
        return when (enabled.size) {
            0 -> LauncherIconState(LauncherIconStyle.fromKey(preferredKey), hidden = true)
            1 -> LauncherIconState(LauncherIconStyle.fromKey(enabled.single()), hidden = false)
            else -> LauncherIconState(LauncherIconStyle.DEFAULT, hidden = false)
        }
    }

    /**
     * 应用目标选择：[style] 为 null 表示隐藏全部。
     *
     * 先启用目标（隐藏时跳过）、再禁用其余；任何一条失败都把整批恢复到 [fallback]
     * （上一个稳定状态），并把恢复结果如实返回 —— 界面用它纠正显示。
     *
     * @return 是否成功达到目标状态
     */
    fun apply(context: Context, style: LauncherIconStyle?, fallback: LauncherIconState?): Boolean {
        val pm = context.packageManager
        val applyOne: (LauncherIconStyle, Boolean) -> Unit = { alias, enable ->
            val state = if (enable) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component(context, alias), state, PackageManager.DONT_KILL_APP)
        }
        // 目标排最前（先启用，再禁用其余）；隐藏时没有「目标」，只剩全禁。
        val ordered = LauncherIconStyle.entries.sortedBy { alias ->
            if (style != null && alias.key == style.key) 0 else 1
        }
        runCatching { ordered.forEach { applyOne(it, style != null && it.key == style.key) } }
            .getOrElse { return false }
        val verified = enabledKeys(context) ?: return false
        val matched = if (style == null) verified.isEmpty() else verified == setOf(style.key)
        if (matched) return true

        // 没达到目标：回滚到上一个稳定状态（同样先启用再禁用）。
        val rollback = LauncherIconStyle.entries.sortedBy { alias ->
            if (fallback != null && !fallback.hidden && alias.key == fallback.style.key) 0 else 1
        }
        runCatching {
            rollback.forEach { applyOne(it, fallback != null && !fallback.hidden && it.key == fallback.style.key) }
        }
        return false
    }
}
