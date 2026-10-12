/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

object NavigationHandleSettings {
    const val FEATURE = "navigation_handle_custom"

    /**
     * 处理方式。
     *
     * 四种效果原先分属四张卡片（`navigation_handle_custom` / `gesture_line` /
     * `navigation_geometry` / `systemui_hide_navigation_bar`），都落在屏幕底部的手势导航区：
     * 隐藏与外观改同一根提示线，几何改承载它的导航栏高度，原生方式改系统自己的隐藏偏好。
     * 合并成一张卡片后用这一行选。
     *
     * **四者互斥**是刻意的：隐藏会把整根提示线的绘制短路，外观与几何却要继续走绘制/测量链，
     * 原生方式又去写系统总开关，同时启用只会互相覆盖 —— 这正是合并前那几张卡片会「打架」的原因。
     * 需要「既隐藏横条又改高度」的组合属于上游也没有的组合，这里明示不给。
     *
     * 最后一项与第二项的区别值得说清楚：两者**最终都是让横条不出现**，但实现层次不同 ——
     * 隐藏方式在模块侧短路 `NavigationHandle` 的绘制，只影响画出来的东西；
     * 原生方式写的是系统自带的 `Settings.Global.hide_gesture_line`，
     * 由 SystemUI 的 NavigationSettings/SettingsManager 与 WMShell 的
     * MultiTaskingSettingsObserver 共同消费，因此连系统自己对「横条是否显示」的判断也一起改。
     * 二者不是同一件事的两套写法，所以作为选项卡共存，而不是二选一删掉一个。
     */
    const val MODE = "$FEATURE.mode"
    const val MODE_APPEARANCE = "1"
    const val MODE_HIDE = "2"
    const val MODE_GEOMETRY = "3"
    const val MODE_NATIVE = "4"

    /**
     * 「隐藏手势横条」并入本卡片后的两个子项。
     *
     * 两者配合才是「稳定地没有」：只拦绘制的话，原生淡入淡出/截屏高亮读到的仍是
     * 「横条显示中」，会出现时隐时现；只报隐藏而不拦绘制同理。所以默认都关，
     * 选择隐藏方式后二者默认开启（见 [HyperOption.defaultEnabled] 的常规语义）。
     *
     * 键名保留 `gesture_line.*`：老用户已经拨过的值直接继续生效，不必写一次搬迁
     * （旧卡片的这些子键与新卡片的子键是同一个键，见 [LEGACY_HIDE_FEATURE]）。
     */
    const val SKIP_DRAW = "gesture_line.skip_draw"
    const val REPORT_HIDDEN = "gesture_line.report_hidden"

    /** 合并前的两张独立卡片主键；迁移时读它们，之后删除。 */
    const val LEGACY_HIDE_FEATURE = "gesture_line"
    const val LEGACY_GEOMETRY_FEATURE = "navigation_geometry"
    /**
     * 合并前的「隐藏底部手势横条」卡片主键与其从属选项；迁移时读它们，之后删除。
     *
     * 那个 `restore_on_disable` 子项合并后**没有保留**：它当年就是死代码 ——
     * 子项的读出条件要求父开关为真（见 `hook/HookSettings.isOn`），而这条路径又只在
     * 父开关为真时才安装，所以「父关着 + 子开着」这个它唯一能生效的状态永远不会出现。
     * 合并后的行为是**按快照恢复**：开启时先记下 `hide_gesture_line` 的原值，
     * 关闭后下次系统界面启动把它写回原值（没有快照 = 我们没改过 = 不需要复位）。
     * 这比一个拨不动的开关更接近它原本想表达的意思，也不需要用户自己判断该不该恢复。
     */
    const val LEGACY_NATIVE_HIDE_FEATURE = "systemui_hide_navigation_bar"
    const val LEGACY_NATIVE_RESTORE = "$LEGACY_NATIVE_HIDE_FEATURE.restore_on_disable"


    const val RADIUS = "$FEATURE.radius"
    const val LIGHT_BACKGROUND = "$FEATURE.light_background"
    const val DARK_BACKGROUND = "$FEATURE.dark_background"
    const val BOTTOM = "$FEATURE.bottom"
    const val WIDTH_PORTRAIT = "$FEATURE.width_portrait"
    const val WIDTH_LANDSCAPE = "$FEATURE.width_landscape"
    val radius = HyperSlider(RADIUS, "提示线半径", 0, 500, 185, step = 5, divisor = 100,
        decimalPlaces = 2, unit = " dp", summary = "线条厚度为半径的两倍；0 保留原生半径，不关闭手势")
    val hideOptions = listOf(
        HyperOption(SKIP_DRAW, "拦截横条绘制", group = "隐藏方式",
            summary = "从绘制入口直接短路，无论谁把它设成可见，画面上都不出现横条"),
        HyperOption(REPORT_HIDDEN, "按原生「隐藏手势提示线」处理", group = "隐藏方式",
            summary = "同时让系统认为横条已隐藏，去掉淡入淡出与截屏高亮；与上一项一起开启效果最稳"),
    )
    val mode = HyperChoice(MODE, "处理方式",
        listOf(
            ChoiceEntry(MODE_APPEARANCE, "自定义提示线外观"),
            ChoiceEntry(MODE_HIDE, "隐藏提示线"),
            ChoiceEntry(MODE_GEOMETRY, "自定义导航栏高度"),
            ChoiceEntry(MODE_NATIVE, "改系统原生隐藏偏好"),
        ),
        summary = "外观方式改用半径、颜色和位置/宽度；隐藏方式从绘制入口短路并让系统按已隐藏处理；几何方式改用下面的导航栏与框架高度；原生方式改系统自己的 hide_gesture_line 偏好，连系统判断一起改。四种互斥")
    val config = listOf(
        mode,
        radius,
        HyperColor(LIGHT_BACKGROUND, "浅色背景下颜色", allowAlpha = true, group = "外观",
            summary = "随系统深浅背景判定平滑混合；可调不透明度，留空保留系统颜色"),
        HyperColor(DARK_BACKGROUND, "深色背景下颜色", allowAlpha = true, group = "外观",
            summary = "随系统深浅背景判定平滑混合；可调不透明度，留空保留系统颜色"),
        HyperSlider(BOTTOM, "提示线距底部", -1, 40, -1, unit = " dp", group = "外观",
            summary = "-1 跟随系统；0 贴底。对应上游禁用的“高度”（底部距离），不是线条厚度；按实际动画空间限制"),
        HyperSlider(WIDTH_PORTRAIT, "竖屏提示线宽度", 0, 400, 0, unit = " dp", group = "外观",
            summary = "0 跟随系统；不改触控区域，按实际视图及按压动画空间限制"),
        HyperSlider(WIDTH_LANDSCAPE, "横屏提示线宽度", 0, 400, 0, unit = " dp", group = "外观",
            summary = "0 跟随系统；不改触控区域，按实际视图及按压动画空间限制"),
    ) + NavigationGeometrySettings.config
    val options = hideOptions
    fun radiusDp(raw: String): Float = (raw.toIntOrNull()?.coerceIn(radius.min, radius.max) ?: radius.default) / 100f
    fun bottomDp(raw: String): Float? = raw.toIntOrNull()?.takeIf { it in 0..40 }?.toFloat()
    fun widthDp(raw: String): Float? = raw.toIntOrNull()?.takeIf { it in 1..400 }?.toFloat()
    /** 归一化的处理方式：空串与未知值按外观处理，与 [mode] 首选项一致。 */
    fun modeOf(raw: String): String = when (raw) {
        MODE_HIDE -> MODE_HIDE
        MODE_GEOMETRY -> MODE_GEOMETRY
        MODE_NATIVE -> MODE_NATIVE
        else -> MODE_APPEARANCE
    }

    /**
     * 系统自带的「隐藏手势提示线」全局偏好键。
     *
     * 原生方式写的就是它；值语义 0 = 显示、1 = 隐藏（真机取证见 `docs/remaining6-resolution`）。
     */
    const val NATIVE_GLOBAL_KEY = "hide_gesture_line"
    const val NATIVE_SHOW = 0
    const val NATIVE_HIDE = 1
    fun nativeValueFor(enabled: Boolean): Int = if (enabled) NATIVE_HIDE else NATIVE_SHOW

    /**
     * 原生隐藏档在系统界面启动时的一次对账决策。
     *
     * 抽成纯函数的原因与本目录其它策略一致：这是**有分支的判断**（开着/关着、有没有快照、
     * 当前值是不是我们自己写的），直接写进 hook 就只能靠真机升级来验证；这里输入输出都是普通值，
     * 单元测试可以覆盖每条分支 —— 包括「恢复时机」这种漏一个分支就会把用户横条永久藏起来的地方。
     *
     * @param enabled 卡片当前开关
     * @param current 系统里 `hide_gesture_line` 的当前值
     * @param snapshot 上次存下的原值；没存过传 null
     * @return 要写什么值、要不要新存一份快照、要不要清掉快照；[write] 为 false 时应当什么都不做
     */
    data class NativeReconcile(
        val write: Boolean,
        val value: Int,
        /** 非 null 表示要把这个值存成新快照（只在还没有快照时才会给出）。 */
        val storeSnapshot: Int?,
        val clearSnapshot: Boolean,
    )

    fun nativeReconcilePlan(enabled: Boolean, current: Int, snapshot: Int?): NativeReconcile {
        if (enabled) {
            // 只在还没有快照、且当前值确实不是我们要写的值时记原值：
            // 否则会把「已经是我们写的 1」当成原值存下来，之后恢复就成了空操作。
            val store = if (snapshot == null && current != NATIVE_HIDE) current else null
            return NativeReconcile(true, NATIVE_HIDE, store, false)
        }
        // 关着：有快照才恢复。没快照 = 我们没改过 = 不需要复位，不是失败。
        return if (snapshot == null) NativeReconcile(false, NATIVE_SHOW, null, false)
        else NativeReconcile(true, snapshot, null, true)
    }
}
