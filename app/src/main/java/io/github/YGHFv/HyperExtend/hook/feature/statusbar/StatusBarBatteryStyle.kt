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
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「电池自定义」。作用域：com.android.systemui。
 *
 * ## 挂三个刷新入口而不是一个
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

    private const val BATTERY_VIEW = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"

    /** 电量百分比被「状态栏图标」那一页整体隐藏了 —— 位置交换与字号就都没有意义了。 */
    private const val OPT_BATTERY_PERCENT_HIDDEN = "status_bar_icons.battery_percent"

    private val REFRESH_METHODS = arrayOf("onBatteryStyleChanged", "updateAll", "updateChargeAndText")

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val wantsChangeLocation = settings.isOn(OPT_CHANGE_LOCATION)
        val wantsCustom = settings.isOn(OPT_CUSTOM)
        if (!wantsChangeLocation && !wantsCustom) return 0

        val view = Reflect.loadClass(loader, BATTERY_VIEW) ?: run {
            ModuleLog.warn("$FEATURE: $BATTERY_VIEW not found")
            return 0
        }

        // 设置在整个进程生命周期里读一次就够：改完开关本来就要重启系统界面（见 HookDispatcher）。
        // 把值先取出来，拦截体里就只剩纯几何计算，不必每次刷新都碰一遍设置代理。
        val style = Style(
            changeLocation = wantsChangeLocation && !settings.isOn(OPT_BATTERY_PERCENT_HIDDEN),
            custom = wantsCustom,
            bold = settings.isOn(OPT_BOLD),
            fontSize = settings.number(KEY_FONT_SIZE, DEFAULT_FONT_SIZE, 0, 200),
            fontSizeMark = settings.number(KEY_FONT_MARK_SIZE, DEFAULT_FONT_SIZE, 0, 200),
            leftMargin = settings.number(KEY_LEFT_MARGIN, 0, 0, 200),
            rightMargin = settings.number(KEY_RIGHT_MARGIN, 0, 0, 200),
            verticalOffset = settings.number(KEY_VERTICAL_OFFSET, DEFAULT_VERTICAL_OFFSET, -200, 200),
            verticalOffsetMark = settings.number(
                KEY_VERTICAL_OFFSET_MARK,
                DEFAULT_VERTICAL_OFFSET_MARK,
                -200,
                200,
            ),
        )

        var installed = 0
        for (name in REFRESH_METHODS) {
            val method = Reflect.findMethods(view, name).firstOrNull() ?: continue
            val ok = HookRuntime.hookAfter(method, "$FEATURE/$name") { chain, original ->
                apply(chain.thisObject, style)
                original
            }
            if (ok) installed++
        }
        if (installed == 0) {
            ModuleLog.warn("$FEATURE: MiuiBatteryMeterView has none of ${REFRESH_METHODS.toList()}")
        }
        return installed
    }

    /**
     * 一次算好的样式。
     *
     * 除布尔量外都是**滑块的存储值**（整数），显示值 = 存储值 / 2 —— 与界面上的
     * 「一个显示单位等于两个存储单位」严格对应，换算只发生在 [apply] 里一处。
     */
    private class Style(
        val changeLocation: Boolean,
        val custom: Boolean,
        val bold: Boolean,
        val fontSize: Int,
        val fontSizeMark: Int,
        val leftMargin: Int,
        val rightMargin: Int,
        val verticalOffset: Int,
        val verticalOffsetMark: Int,
    )

    private fun apply(target: Any?, style: Style) {
        if (target == null) return
        val percent = Reflect.readField(target, "mBatteryPercentView") as? TextView ?: return
        val mark = Reflect.readField(target, "mBatteryPercentMarkView") as? TextView

        if (style.changeLocation) changeLocation(percent, mark)
        if (!style.custom) return

        val fontSize = style.fontSize * 0.5f
        if (fontSize > MIN_USEFUL_FONT_SIZE) {
            percent.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fontSize)
        }
        val fontSizeMark = style.fontSizeMark * 0.5f
        if (mark != null && fontSizeMark > MIN_USEFUL_FONT_SIZE) {
            mark.setTextSize(TypedValue.COMPLEX_UNIT_DIP, fontSizeMark)
        }
        if (style.bold) {
            percent.typeface = Typeface.DEFAULT_BOLD
            mark?.typeface = Typeface.DEFAULT_BOLD
        }

        val density = percent.resources.displayMetrics.density
        val px = { dp: Float -> (dp * density).toInt() }
        val left = px(style.leftMargin * 0.5f)
        val right = px(style.rightMargin * 0.5f)
        // 偏移量是按「离默认值多远」给的：默认 12（= 6dp）表示不偏移。
        val top = if (style.verticalOffset != DEFAULT_VERTICAL_OFFSET) {
            px((style.verticalOffset - DEFAULT_VERTICAL_OFFSET) * 0.5f)
        } else {
            0
        }
        // 百分号是这一排里最右边那个元素，所以右边距归它；数字只负责左边距与上下偏移。
        percent.setPaddingRelative(left, top, if (mark == null) right else 0, 0)
        if (mark != null) {
            // 百分号那一档的门限与数字不同（参考项目用的就是 8 这个基准），照抄。
            val markTop = if (style.verticalOffsetMark != DEFAULT_VERTICAL_OFFSET_MARK) {
                px((style.verticalOffsetMark - MARK_OFFSET_BASELINE) * 0.5f)
            } else {
                top
            }
            mark.setPaddingRelative(0, markTop, right, 0)
        }
    }

    /**
     * 把「数字 + 百分号」整体挪到所在容器的第一位 —— 也就是与电池图标换了个位置。
     *
     * 判据是「第一位是不是已经是它」：这三条刷新路径在一次充电里会被走到很多次，
     * 每次都无条件重排会让视图反复从布局里摘掉再加回去（动画与焦点都会受影响）。
     */
    private fun changeLocation(percent: TextView, mark: TextView?) {
        val parent = percent.parent as? ViewGroup ?: return
        if (parent.getChildAt(0) === percent) return
        parent.removeView(percent)
        if (mark != null) parent.removeView(mark)
        parent.addView(percent, 0)
        if (mark != null) parent.addView(mark, 1)
    }

    /** 参考项目里「小于 7.5dp 就不动」那条门限：再小的字没人看得见，只是把布局弄乱。 */
    private const val MIN_USEFUL_FONT_SIZE = 7.5f

    /** 百分号偏移量的基准值（参考项目里那个写死的 8）。 */
    private const val MARK_OFFSET_BASELINE = 8
}
