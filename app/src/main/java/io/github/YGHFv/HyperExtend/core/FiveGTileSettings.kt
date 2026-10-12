/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 独立 5G 磁贴的**样式选择** —— 上游 `prefs_key_system_control_center_5g_new_tile`
 * 这个下拉框的迁移落点。
 *
 * ## 为什么它不是一张新卡片
 *
 * 上游把「补全磁贴列表」和一个 5G 样式下拉框并排放；两者动的是同一个磁贴候选列表，
 * 拆成两张卡片的结果是「第一张开着、第二张关着」时用户根本说不清 5G 磁贴该长什么样。
 * 所以样式是 [OWNER] 卡片的**一行配置**，不是独立开关。
 *
 * ## 三种样式各自的载体（真机取证，见 docs/five-g-style-evidence.json）
 *
 * - **样式一 / 样式二**：都是模块自己实现的独立磁贴，区别只在图标。
 *   上游两份 on/off 图标按字节比对是「同一张图两套画法」——
 *   `ic_control_center_5g_toggle_on(_off)` 互为副本、`_v2_on(_off)` 互为副本，
 *   而 `_v2` 与本模块已有的 `ic_qs_five_g` 哈希相同。所以这里只需要新增样式一那张图。
 * - **内置移动网络磁贴**：不新增磁贴，改为往**原生**移动网络磁贴的二级详情页里
 *   注入一项「5G网络」，由宿主自己的 `MiuiCellularTile$CellularDetailAdapter`
 *   渲染。这是上游 mode 3 的做法，本模块按同一路径重新实现（见 hook 侧）。
 *
 * 取值为字符串，与其它多选一行一致；不认识的取值按样式二（本模块原有外观）处理，
 * 而不是回落到「内置」——那会让一次写坏的设置把磁贴整个换掉。
 */
object FiveGTileSettings {
    /**
     * 承载样式的卡片 id。这里写死字面量而不是引用 hook 侧的 `StockTilesPolicy.FEATURE`：
     * `core` 不能反过来依赖 `hook`，而两处本来就该是同一个键（hook 侧引用本常量）。
     */
    const val OWNER = "control_center_fix_tiles_list"

    const val STYLE = "$OWNER.5g_style"
    const val STYLE_V1 = "1"
    const val STYLE_V2 = "2"
    const val STYLE_NATIVE = "3"

    /** 样式行。顺序即下拉顺序，第一项是出厂值。 */
    val style = HyperChoice(
        STYLE, "5G 开关样式",
        listOf(
            ChoiceEntry(STYLE_V1, "样式一"),
            ChoiceEntry(STYLE_V2, "样式二"),
            ChoiceEntry(STYLE_NATIVE, "内置移动网络磁贴"),
        ),
        summary = "样式一/样式二都是独立磁贴，只换图标；选「内置移动网络磁贴」则不新增磁贴，" +
            "改为在原生移动网络详情页里加一项 5G 开关",
    )

    /** 归一化：只认三种已登记取值，其余按样式二。 */
    fun normalize(raw: String): String = when (raw) {
        STYLE_V1, STYLE_V2, STYLE_NATIVE -> raw
        else -> STYLE_V2
    }

    /** 这一档是否走「原生移动网络磁贴详情页」而不是独立磁贴。 */
    fun usesNativeDetail(raw: String): Boolean = normalize(raw) == STYLE_NATIVE

    /** 独立磁贴用哪个图标资源名；原生档不看图标。 */
    fun iconAsset(raw: String): String =
        if (normalize(raw) == STYLE_V1) "ic_qs_five_g_style1" else "ic_qs_five_g"
}
