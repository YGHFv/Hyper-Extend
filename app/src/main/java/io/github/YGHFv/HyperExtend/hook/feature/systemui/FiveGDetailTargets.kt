/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/**
 * 「内置移动网络磁贴」档要碰的宿主成员，逐条来自本机 dex 取证
 * （见 docs/five-g-style-evidence.json）。
 */
internal object FiveGDetailTargets {
    const val CONTENT = "com.android.systemui.qs.QSDetailContent"
    const val ITEM = "$CONTENT\$Item"
    const val SELECTABLE = "$CONTENT\$SelectableItem"
    const val TOGGLE = "$CONTENT\$ToggleItem"
    const val DIVIDER = "$CONTENT\$TextDividerItem"
    const val ITEM_HOLDER = "$CONTENT\$ItemHolder"
    const val CONTENT_ADAPTER = "$CONTENT\$Adapter"
    const val ADAPTER = "com.android.systemui.qs.tiles.MiuiCellularTile\$CellularDetailAdapter"
    const val TILE_BASE = "com.android.systemui.qs.tileimpl.QSTileImpl"

    /**
     * 数组形参必须写成 `[L<类名>;`（`Class.getName()` 的写法），**L 不能省**。
     *
     * 少写这个 `L` 会让 `SystemUiMethod.matches` 里的
     * `method.parameterTypes.map { it.name } == parameters` 永远不成立：
     * `Item[].class.getName()` 是 `[Lcom/.../QSDetailContent$Item;`，
     * 而 `[$ITEM;` 展开成 `[com/.../QSDetailContent$Item;`。
     * 结果是 hook 静默装不上 —— 日志里只留一行
     * `systemui target missing: ...QSDetailContent#setItems[[com...Item;]`，
     * 而「内置移动网络磁贴」整档什么也不做。
     *
     * 这条回归由 `FiveGTilePolicyTest.nativeDetailCarriersMatchTheHostExactly` 钉住。
     */
    val setItems = SystemUiMethod(CONTENT, "setItems", "void", listOf("[L$ITEM;"))
    val onDetailItemClick = SystemUiMethod(ADAPTER, "onDetailItemClick", "void", listOf(ITEM))

    /**
     * 条目绑定收尾，用来给注入的 5G 行补组内间距。
     *
     * 宿主这个方法的 `ToggleItem` 分支**只设 `bottomMargin`**，且只在
     * 「这一项正好是最后一项 && 非控制中心模式」时给
     * `qs_detail_toggle_item_last_margin_bottom`（= 22dp），其余情况为 0；
     * 它**从不设置 `topMargin`**。因此**两个相邻的 toggle 之间永远是 0 间距**，
     * 宿主自己只放一个 toggle（智能双卡）所以从没暴露这条。
     *
     * 注入的 5G 排在智能双卡前面，于是变成「非末位 toggle」→ 上下间距全 0，
     * 与双卡贴成一片；两张独立圆角卡贴死又让圆角看起来不对。
     *
     * 这里用**非 bridge 的那条重载**（形参是 `ItemHolder`，不是
     * `RecyclerView$ViewHolder`），bridge 版本最终也会转发到它。
     */
    val onBindViewHolder = SystemUiMethod(
        CONTENT_ADAPTER, "onBindViewHolder", "void", listOf(ITEM_HOLDER, "int"))

    /** `QSDetailContent.items`（私有数组，收尾 hook 用它按位置取条目）。 */
    const val ITEMS_FIELD = "items"
    /** `RecyclerView$ViewHolder.itemView`。 */
    const val HOLDER_ITEM_VIEW = "itemView"
    /**
     * 组内卡片间距基准。宿主 SIM 卡之间取 `2 ×` 这个值（见
     * `onBindViewHolder` 的 `:goto_2de` 分支 `mul-int/2addr v4, v8`），
     * 我们给注入行补同样的量，保证卡片间距与原生一致。
     */
    const val CARD_MARGIN_DIMEN = "qs_detail_selectable_item_margin_vertical"

    /** 与独立磁贴同一条 telephony 路径。 */
    val managerGet = SystemUiMethod("miui.telephony.TelephonyManagerEx", "getDefault", "miui.telephony.TelephonyManagerEx", isStatic = true)
    val binderGet = SystemUiMethod("miui.telephony.TelephonyManagerEx", "getMiuiTelephony", "miui.telephony.IMiuiTelephony")
    val capable = SystemUiMethod("miui.telephony.IMiuiTelephony", "isFiveGCapable", "boolean")
    val enabled = SystemUiMethod("miui.telephony.IMiuiTelephony", "isUserFiveGEnabled", "boolean", listOf("int"))
    val setEnabled = SystemUiMethod("miui.telephony.IMiuiTelephony", "setUserFiveGEnabled", "void", listOf("boolean", "int"))
    val currentApplication = SystemUiMethod("android.app.ActivityThread", "currentApplication", "android.app.Application", isStatic = true)

    val methods = listOf(setItems, onDetailItemClick, onBindViewHolder)
}
