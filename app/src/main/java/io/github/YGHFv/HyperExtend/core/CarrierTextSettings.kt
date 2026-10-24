/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 控制中心**运营商名称**的处理方式 —— 上游 `CustomCarrierText` / `HideDelimiter` 那条下拉框的落点。
 *
 * ## 为什么从纯开关改成四选一
 *
 * 上游这两个规则名读起来像两件事，实际共用同一个偏好键
 * `system_ui_control_center_hide_operator`，取值 `0=默认 / 1=去掉分隔符 / 2=隐藏名称 / 3=显示设备名称`。
 * 本卡片原先只迁了 `2`（且做成了一个布尔开关），于是用户能做的只有「整块隐藏」：
 * 想把「中国移动 | 中国联通」压成一行、或者干脆显示自己的设备名，都办不到。
 * 四个取值作用在**同一处文本**上，互斥是数据本身保证的，不该拆成四个开关让用户自己保证只开一个。
 *
 * ## 为什么卡片主键保持 `control_center_hide_carrier`
 *
 * 它是老布尔开关的键。保住它意味着：**老用户的开关不用改名**，
 * 只需要在 schema v7→v8 里把「布尔为真」显式搬成 `mode=2`
 * （否则老用户升级后 mode 读成默认的 `0`，等于他正在用的隐藏被静默关掉）。
 * 见 [MergedCardMigration.carrierTextModePlan]。
 *
 * ## 各档的作用层次（这是本文件最重要的约定）
 *
 * - `1` 与 `3` 改的是**分发给监听者的文本**：拦 `MiuiCarrierTextController#fireCarrierTextChanged`
 *   那一个汇聚点，四个监听者（控制中心两处、锁屏状态栏、超级岛）一起跟着变。
 *   `1` 还要额外把分隔符视图置 `INVISIBLE`：双卡时宿主是「左文本 + 分隔条 + 右文本」三个视图，
 *   只改文本会让分隔条留在原地。
 * - `2` **不改文本**，只把整块 `carrierLayout` 置 `INVISIBLE`。这是刻意的：
 *   把文本改成空串会让宿主自己走到「无服务」分支去动可见性与无障碍标签，
 *   那就不是「保留占位、只隐藏名称」了，而是改变宿主的显示判定。
 *   所以 `2` 与 `1`/`3` 挂在**不同的方法**上，互不重叠。
 *
 * 一句话：文本档只写文本，隐藏档只写可见性。不要把 `2` 也接到 fire 上「顺手统一实现」。
 */
object CarrierTextSettings {
    /** 卡片主键，沿用合并前的布尔开关键。 */
    const val FEATURE = "control_center_hide_carrier"

    const val MODE = "$FEATURE.mode"
    const val MODE_DEFAULT = "0"
    const val MODE_SEPARATOR = "1"
    const val MODE_HIDE = "2"
    const val MODE_DEVICE_NAME = "3"

    val mode = HyperChoice(
        MODE, "处理方式",
        listOf(
            ChoiceEntry(MODE_DEFAULT, "保持原生"),
            ChoiceEntry(MODE_SEPARATOR, "去掉双卡分隔符"),
            ChoiceEntry(MODE_HIDE, "隐藏运营商名称"),
            ChoiceEntry(MODE_DEVICE_NAME, "显示设备名称"),
        ),
        summary = "四种处理方式作用在同一处运营商文本上，只能选一个。隐藏名称只收起名称区域，" +
            "不动隐私提示；显示设备名称读取系统设置里的设备名，读不到时保持原生文本。",
    )
    val config = listOf(mode)

    /** 归一化取值：空串与未知值一律按「保持原生」，与上游下拉框的缺省项一致。 */
    fun modeOf(raw: String?): String = when (raw) {
        MODE_SEPARATOR -> MODE_SEPARATOR
        MODE_HIDE -> MODE_HIDE
        MODE_DEVICE_NAME -> MODE_DEVICE_NAME
        else -> MODE_DEFAULT
    }

    /** 本档是否需要改写分发文本（`1`/`3`）。 */
    fun rewritesText(raw: String?): Boolean = modeOf(raw).let { it == MODE_SEPARATOR || it == MODE_DEVICE_NAME }

    /** 本档是否需要收起名称区域（`2`）。 */
    fun hidesCarrierBlock(raw: String?): Boolean = modeOf(raw) == MODE_HIDE

    /** 本档是否需要额外隐藏分隔符视图（只有 `1`）。 */
    fun hidesSeparator(raw: String?): Boolean = modeOf(raw) == MODE_SEPARATOR
}
