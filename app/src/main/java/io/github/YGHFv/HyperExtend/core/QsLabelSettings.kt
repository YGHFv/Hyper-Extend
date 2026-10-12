/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 「隐藏快捷开关标签」—— 上游 `prefs_key_system_ui_qs_label` 的落地裁定。
 *
 * ## 上游只有一个开关，没有实现
 *
 * 上游 `system_ui_other.xml` 里这一行被放在 `app:isPreferenceVisible="false"` 的分类里，
 * 字符串是「快捷开关标签」，但 **发布版的 hook 代码里没有任何一处读它**：
 * 完整 DEX 名搜索 `HideQsLabel` / `qsLabel` / `QS_label` 在 HyperCeiler 2.10.166 里零命中，
 * 只有资源名 `system_ui_qs_label` 与 `QS_label`（一个布局 id）。
 * 也就是说这个键当年就是一个没写完的入口。
 *
 * ## 当前固件里确实存在的载体
 *
 * 真机 `MiuiSystemUI.apk`（202602260）的 `com.android.systemui.qs.tileimpl.MiuiQSTileView`
 * 里有一组只服务于标签的字段：`mLabel:TextView`、`mLabelContainer:ViewGroup`，
 * 并且 `handleStateChanged(QSTile$State)` 会**逐次刷新**标签文本、
 * `mLabelContainer` 的无障碍重要度、内容描述与可点击性 —— 但它从不设置
 * `mLabelContainer` 的 visibility。所以「让标签不显示」有一个现成的原生落点：
 * 把 `mLabelContainer` 置为 `GONE`，原生文本与状态照旧写入，只是不参与布局与绘制。
 *
 * ## 边界
 *
 * - 只处理当前固件的经典 SystemUI 磁贴视图；控制中心插件（`miui.systemui.plugin`）
 *   用的是另一套 ViewHolder，不在本功能范围内，保持原生。
 * - 隐藏的是**可见标签**。磁贴的 `contentDescription` 与状态描述来自原生
 *   `QSTile$State`，不受影响，所以读屏仍然能报出「无线局域网 已开启」这类语义。
 * - 还原就是把它设回 `VISIBLE`，因为原生的 `handleStateChanged` 不管理这个可见性，
 *   不存在「原生本来就想让它隐藏」的情况被我们改掉。
 * - 关闭功能后需要重启系统界面才会按新状态重排（hook 只在安装时决定装不装）。
 */
object QsLabelSettings {
    const val FEATURE = "systemui_qs_label"

    /** 本模块对上游这个空开关给出的用户可见行为：隐藏磁贴上的文字标签。 */
    const val TITLE = "隐藏快捷开关标签"
}
