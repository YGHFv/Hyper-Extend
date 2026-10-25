/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

/**
 * 状态栏「流量节省」图标（上游 `DataSaverIconOS4`）的当前宿主成员。
 *
 * 逐字签名与字段留档在 `app/src/test/resources/data-saver-icon-current-host-members.txt`，
 * 由 `StatusIconMigrationTest` 断言，证据取自 SystemUI 的 classes2.dex 全量 dexdump。
 *
 * ## 为什么不能只靠块名单
 *
 * 本模块已有的 [IconBlockListHooks] 只决定「已有 holder 的槽位显示在哪里」。
 * 而当前宿主自己**从不给流量节省槽位建 holder**：
 *
 * - `PhoneStatusBarPolicy.onDataSaverChanged(Z)V`（偏移 `6fa4b8`，`PUBLIC FINAL`，仅 10 条指令）
 *   的方法体只有 `mSlotDataSaver` + `mIconController` 两个字段读取与一次
 *   `StatusBarIconControllerImpl.setIconVisibility(String,Z)V`，**没有** `setIcon`。
 * - `mSlotDataSaver` 在全 dex 只出现三处：字段声明、`<init>` 里的赋值、
 *   以及上面那一次读取 —— 也就是说没有任何地方调用 `setIcon(slot=流量节省)`。
 * - 宿主自己的 `PhoneStatusBarPolicy` 在启动路径里逐个 `setIcon(描述串, 槽位, drawableId)`
 *   给闹钟（`6e2d52`）等槽位建 holder，唯独跳过了流量节省。
 *
 * 结论：`onDataSaverChanged` 里的 `setIconVisibility` 在本宿主是**空操作**，
 * 「始终显示」不可能自己冒出来。上游 `DataSaverIconOS4` 正是补了这一段：
 * 在可见性回调里、槽位还没有 holder 时按宿主自己的方式建一个。
 */
internal object DataSaverIconTargets {
    const val POLICY = "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy"
    const val CONTROLLER = "com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl"
    const val ICON_LIST = "com.android.systemui.statusbar.phone.ui.StatusBarIconList"
    const val HOLDER = "com.android.systemui.statusbar.phone.StatusBarIconHolder"
    const val CONTROLLER_INTERFACE = "com.android.systemui.statusbar.phone.ui.StatusBarIconController"

    /** 目标槽位名；与 `framework-res` 的 `status_bar_data_saver` 解出来的一致。 */
    const val SLOT = "data_saver"

    /** 建 holder 用的资源：`SystemUI` 资源表里存在 `stat_sys_data_saver`（6 处）。 */
    const val DRAWABLE = "stat_sys_data_saver"
    const val DESCRIPTION = "data_saver"
    const val PACKAGE = "com.android.systemui"

    /** holder 的 tag；宿主自己查/建 holder 用的都是 0。 */
    const val TAG = 0

    /** 宿主唯一的流量节省可见性入口；方法体只调用 `setIconVisibility`。 */
    val callback = SystemUiMethod(POLICY, "onDataSaverChanged", "void", listOf("boolean"))

    /** 宿主给其它槽位建 holder 用过的那个重载：描述串、槽位名、drawable 资源 id。 */
    val setIcon = SystemUiMethod(CONTROLLER, "setIcon", "void",
        listOf("java.lang.CharSequence", "java.lang.String", "int"))

    /** 查槽位已有 holder；返回 null 表示宿主从未给这个槽位建过 holder。 */
    val getHolder = SystemUiMethod(ICON_LIST, "getIconHolder", HOLDER, listOf("int", "java.lang.String"))

    val fields = listOf(
        Triple(POLICY, "mSlotDataSaver", "java.lang.String"),
        Triple(POLICY, "mResources", "android.content.res.Resources"),
        Triple(POLICY, "mIconController", CONTROLLER_INTERFACE),
        Triple(CONTROLLER, "mStatusBarIconList", ICON_LIST),
    )
}
