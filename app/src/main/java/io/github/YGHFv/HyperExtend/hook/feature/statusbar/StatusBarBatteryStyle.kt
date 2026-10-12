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
 *
 * ---------------------------------------------------------------------------
 * 功能来源：西米露 / HyperCeiler · BatteryStyle（AGPL-3.0）。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「电池自定义」。作用域：com.android.systemui。
 *
 * ## 挂四个精确刷新入口而不是一个
 *
 * 电池视图有好几条刷新路径：切换样式、整体刷新、充放电与文字刷新。它们的调用时机不同
 * （插上充电器、切换深浅色、改字号……），只挂其中一个的结果是「刚开始好了，充一次电又回去了」——
 * 这种时好时坏的现象最难被当成「开关没生效」报上来。
 *
 * ## 与参考项目的两处差异（都是因为 OS4 的字段变了）
 *
 * - 参考项目改的 `mBatteryTextDigitView` 在 OS4 上**不存在**：电量数字改由
 *   `mBatteryPercentView` 自己承担，所以字号与加粗只作用在它和百分号上。
 * - 参考项目挂的 `updateAll$1`（Kotlin 的 lambda 桥接方法）在 OS4 上也已经没有了，
 *   对应的入口是 `updateAll()`。名字不同、语义相同。
 */
internal object StatusBarBatteryStyle {

    private const val FEATURE = "status_bar_battery_style"

    private const val OPT_CHANGE_LOCATION = "status_bar_battery_style.change_location"
    private const val OPT_CUSTOM = "status_bar_battery_style.custom"
    private const val OPT_BOLD = "status_bar_battery_style.bold"

    private const val KEY_FONT_SIZE = "status_bar_battery_style.font_size"
    private const val KEY_FONT_MARK_SIZE = "status_bar_battery_style.font_mark_size"
    private const val KEY_LEFT_MARGIN = "status_bar_battery_style.left_margin"
    private const val KEY_RIGHT_MARGIN = "status_bar_battery_style.right_margin"
    private const val KEY_VERTICAL_OFFSET = "status_bar_battery_style.vertical_offset"
    private const val KEY_VERTICAL_OFFSET_MARK = "status_bar_battery_style.vertical_offset_mark"

    /** 与目录里登记的字号默认值一致；滑块的存储值是一个显示单位的两倍，所以这里按一半用。 */
    private const val DEFAULT_FONT_SIZE = 15
    private const val DEFAULT_VERTICAL_OFFSET = 12
    private const val DEFAULT_VERTICAL_OFFSET_MARK = 27

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val wantsChangeLocation = settings.isOn(OPT_CHANGE_LOCATION)
        val wantsCustom = settings.isOn(OPT_CUSTOM)
        if (!wantsChangeLocation && !wantsCustom) return 0
        val orderHooks = if (wantsChangeLocation) runCatching { BatteryOrderHooks.install(loader) }.getOrDefault(0) else 0
        if (!wantsCustom) return orderHooks

        val t = BatteryStyleTargets
        val methods = (t.refresh + t.callers).associateWith { NotificationHooks.resolve(loader, it) ?: return orderHooks }
        val view = Class.forName(t.OWNER, false, loader)
        val fields = t.fields.associate { (name, expected) ->
            name to view.getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$FEATURE/caller") }) return orderHooks

        // 设置在整个进程生命周期里读一次就够：改完开关本来就要重启系统界面（见 HookDispatcher）。
        // 把值先取出来，拦截体里就只剩纯几何计算，不必每次刷新都碰一遍设置代理。
        val visibility = BatteryVisibilityPolicy.from(settings)
        val style = Style(
            visibility = visibility,
            custom = wantsCustom,
            bold = settings.isOn(OPT_BOLD),
            fontSize = settings.number(KEY_FONT_SIZE, DEFAULT_FONT_SIZE, 15, 40),
            fontSizeMark = settings.number(KEY_FONT_MARK_SIZE, DEFAULT_FONT_SIZE, 15, 40),
            leftMargin = settings.number(KEY_LEFT_MARGIN, 0, 0, 10),
            rightMargin = settings.number(KEY_RIGHT_MARGIN, 0, 0, 10),
            verticalOffset = settings.number(KEY_VERTICAL_OFFSET, DEFAULT_VERTICAL_OFFSET, 0, 24),
            verticalOffsetMark = settings.number(
                KEY_VERTICAL_OFFSET_MARK,
                DEFAULT_VERTICAL_OFFSET_MARK,
                0,
                27,
            ),
        )

        val ready = AtomicBoolean(false)
        var version: Long? = null
        val hooks = t.refresh.map { target ->
            HookRuntime.hookAfter(methods.getValue(target), "$FEATURE/${target.name}") { chain, original ->
                if (!ready.get() || Looper.myLooper() != Looper.getMainLooper()) return@hookAfter original
                val root = chain.thisObject as? View ?: return@hookAfter original
                val allowed = runCatching {
                    val host = version ?: root.context.packageManager.getPackageInfo("com.android.systemui", 0)
                        .compatibleVersionCode.also { version = it }
                    host == 202602260L && (root.display?.displayId ?: root.context.hostDisplayIdOrNull()) == 0
                }.getOrDefault(false)
                if (allowed) apply(root, style, fields)
                original
            }
        }
        ready.set(hooks.all { it })
        return orderHooks + if (ready.get()) hooks.size else 0
    }

    /**
     * 一次算好的样式。
     *
     * 除布尔量外都是**滑块的存储值**（整数），显示值 = 存储值 / 2 —— 与界面上的
     * 「一个显示单位等于两个存储单位」严格对应；字号由共用策略换算，边距在 [apply] 换算。
     */
    private class Style(
        val visibility: BatteryVisibilityPolicy,
        val custom: Boolean,
        val bold: Boolean,
        val fontSize: Int,
        val fontSizeMark: Int,
        val leftMargin: Int,
        val rightMargin: Int,
        val verticalOffset: Int,
        val verticalOffsetMark: Int,
    )

    private fun apply(target: View, style: Style, fields: Map<String, Field>) {
        val percent = fields.getValue("mBatteryPercentView").get(target) as? TextView ?: return
        val mark = fields.getValue("mBatteryPercentMarkView").get(target) as? TextView

        if (!style.custom) return

        style.visibility.percentSizeDp(style.fontSize)?.let { size ->
            percent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        }
        style.visibility.markSizeDp(style.fontSizeMark)?.let { size ->
            mark?.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        }
        if (style.visibility.hidePercent) return
        if (style.bold) {
            percent.typeface = Typeface.create(percent.typeface, Typeface.BOLD)
            if (!style.visibility.hidesMark && mark != null) mark.typeface = Typeface.create(mark.typeface, Typeface.BOLD)
        }

        val (numberPadding, markPadding) = BatteryStylePolicy.padding(style.leftMargin, style.rightMargin,
            style.verticalOffset, style.verticalOffsetMark, percent.resources.displayMetrics.density,
            mark != null, mark?.visibility == View.VISIBLE, style.visibility.hidesMark, mark?.paddingTop ?: 0) ?: return
        // A hidden percent mark must not consume the visible number's trailing margin.
        percent.setPaddingRelative(numberPadding.start, numberPadding.top, numberPadding.end, 0)
        if (mark != null) {
            mark.setPaddingRelative(markPadding.start, markPadding.top, markPadding.end, 0)
        }
    }

}
