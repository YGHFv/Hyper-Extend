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
 * 功能来源：西米露 / HyperCeiler · NewNetworkSpeed、NewNetworkSpeedStyle、
 * NetworkSpeedSec、NetworkSpeedSpacing（AGPL-3.0）。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.graphics.Typeface
import android.net.TrafficStats
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import android.os.Handler
import android.os.Message
import java.util.WeakHashMap

/** Host text remains a two-slot number/unit pair; only the background queue is rescheduled. */
internal object StatusBarNetworkSpeed {

    private const val FEATURE = "status_bar_network_speed"

    private const val OPT_FONT_SIZE_ENABLE = "status_bar_network_speed.font_size_enable"
    private const val OPT_HIDE = "status_bar_network_speed.hide"
    private const val OPT_HIDE_ALL = "status_bar_network_speed.hide_all"
    private const val OPT_SEC_UNIT = "status_bar_network_speed.sec_unit"
    private const val OPT_SWAP = "status_bar_network_speed.swap_places"

    private const val KEY_FONT_SIZE = "status_bar_network_speed.font_size"
    private const val KEY_FONT_STYLE = "status_bar_network_speed.font_style"
    private const val KEY_HIDE_SLOW = "status_bar_network_speed.hide_slow"
    private const val KEY_UPDATE_SPACING = "status_bar_network_speed.update_spacing"
    private const val KEY_STYLE = "status_bar_network_speed.style"
    private const val KEY_ICON = "status_bar_network_speed.icon"
    private const val KEY_ALIGN = "status_bar_network_speed.align"
    private const val KEY_FIXED_WIDTH = "status_bar_network_speed.fixedcontent_width"
    private const val KEY_SPACING_MARGIN = "status_bar_network_speed.spacing_margin"
    private const val KEY_LEFT_MARGIN = "status_bar_network_speed.left_margin"
    private const val KEY_RIGHT_MARGIN = "status_bar_network_speed.right_margin"
    private const val KEY_VERTICAL_OFFSET = "status_bar_network_speed.vertical_offset"

    private const val CONTROLLER = "com.android.systemui.statusbar.policy.NetworkSpeedController"
    private const val SPEED_VIEW = "com.android.systemui.statusbar.views.NetworkSpeedView"

    /** 单位后缀里那几个字符。开了「隐藏 *b/s 单位」就按它们裁掉。 */
    private val UNIT_CHARS = listOf("/", "B", "s", "'", "วิ")

    /** 速度视图里重新排版的几个入口（字号变化、主题变化、重新 inflate 都会重排）。 */
    private val VIEW_RESTYLE_METHODS =
        arrayOf("onFinishInflate", "onDensityOrFontScaleChanged", "onMiuiThemeChanged", "updateResources\$15")

    private val samplers = WeakHashMap<Any, NetworkSpeedSampler>()

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val config = Config.of(settings)
        var installed = 0
        installed += installUpdateText(loader, config)
        installed += installViewStyle(loader, config)
        installed += installUnitSuffix(loader, settings)
        installed += installUpdateInterval(loader, config)
        return installed
    }

    // ------------------------------------------------------------------ 速率文本

    private fun installUpdateText(loader: ClassLoader, config: Config): Int {
        val controller = Reflect.loadClass(loader, CONTROLLER) ?: run {
            ModuleLog.warn("$FEATURE: $CONTROLLER not found")
            return 0
        }
        val updateText = Reflect.firstMethod(controller, "updateText") {
            it.parameterTypes.contentEquals(arrayOf(Array<String>::class.java))
        } ?: run {
            ModuleLog.warn("$FEATURE: NetworkSpeedController#updateText(String[]) is gone")
            return 0
        }
        val ok = HookRuntime.hook(updateText, "$FEATURE/NetworkSpeedController#updateText") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val speeds = synchronized(samplers) {
                samplers.getOrPut(owner) { NetworkSpeedSampler() }.sample(
                    System.nanoTime(), TrafficStats.getTotalTxBytes(), TrafficStats.getTotalRxBytes())
            }
            val replacement = NetworkSpeedText.render(speeds.first, speeds.second, config.text)
            if (replacement == null) chain.proceed() else chain.proceed(arrayOf<Any?>(replacement))
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ 视图样式

    private fun installViewStyle(loader: ClassLoader, config: Config): Int {
        val view = Reflect.loadClass(loader, SPEED_VIEW) ?: run {
            ModuleLog.warn("$FEATURE: $SPEED_VIEW not found — view style skipped")
            return 0
        }
        var installed = 0
        for (name in VIEW_RESTYLE_METHODS) {
            val method = Reflect.findMethods(view, name).firstOrNull() ?: continue
            val ok = HookRuntime.hookAfter(method, "$FEATURE/NetworkSpeedView#$name") { chain, original ->
                restyle(chain.thisObject as? View ?: return@hookAfter original, config)
                original
            }
            if (ok) installed++
        }
        return installed
    }

    private fun restyle(meter: View, config: Config) {
        val number = Reflect.readField(meter, "mNetworkSpeedNumberText") as? TextView ?: return
        val unit = Reflect.readField(meter, "mNetworkSpeedUnitText") as? TextView

        if (config.fixedWidth > DEFAULT_FIXED_WIDTH) {
            val width = (meter.resources.displayMetrics.density * config.fixedWidth).toInt()
            val params = meter.layoutParams ?: ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            params.width = width
            meter.layoutParams = params
        }

        val single = arrayOf(number, unit)
        if (config.style != STYLE_DEFAULT) {
            // Custom text includes its unit, but still supplies the host's empty second slot.
            unit?.visibility = View.GONE
            if (config.style == STYLE_VALUE_UNIT_TWO_LINE || config.style == STYLE_TX_RX_TWO_LINE) {
                number.isSingleLine = false
                number.maxLines = 2
                number.setLineSpacing(0f, config.lineSpacing)
            }
        }
        single.filterNotNull().forEach { target ->
            applyFont(target, config)
            applyPadding(target, config)
        }
        applyAlign(number, config)
    }

    private fun applyFont(target: TextView, config: Config) {
        when (config.fontStyle) {
            1 -> target.typeface = Typeface.DEFAULT
            2 -> target.typeface = Typeface.DEFAULT_BOLD
        }
        if (!config.fontSizeEnabled) return
        // 双排与自定义样式下，宿主本来按「半边高」排的字号再缩一半才好放下。
        val size = when (config.style) {
            STYLE_DEFAULT, STYLE_VALUE_UNIT_TWO_LINE, STYLE_TX_RX_TWO_LINE -> config.fontSize * 0.5f
            else -> config.fontSize.toFloat()
        }
        target.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
    }

    private fun applyPadding(target: TextView, config: Config) {
        val density = target.resources.displayMetrics.density
        val left = (config.leftMargin * 0.5f * density).toInt()
        val right = (config.rightMargin * 0.5f * density).toInt()
        val top = if (config.verticalOffset != DEFAULT_VERTICAL_OFFSET) {
            ((config.verticalOffset - DEFAULT_VERTICAL_OFFSET) * 0.1f * density).toInt()
        } else {
            0
        }
        target.setPaddingRelative(left, top, right, 0)
        if (config.style != STYLE_DEFAULT) {
            target.translationX = 0f
            target.translationY = 0f
        }
    }

    private fun applyAlign(number: TextView, config: Config) {
        number.gravity = when (config.align) {
            2 -> Gravity.START or Gravity.CENTER_VERTICAL
            4 -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER
        }
    }

    // ------------------------------------------------------------------ 单位后缀

    /**
     * 「网速隐藏 *b/s 单位」。
     *
     * 拦的是视图那个 `setNetworkSpeed(value, unit)`：把单位里那几个字符抹掉，
     * 数字那一半原样放行。这样「默认样式」下也能只留数字。
     */
    private fun installUnitSuffix(loader: ClassLoader, settings: HookSettings): Int {
        if (!settings.isOn(OPT_SEC_UNIT)) return 0
        val view = Reflect.loadClass(loader, SPEED_VIEW) ?: return 0
        val method = Reflect.findMethods(view, "setNetworkSpeed", 2).firstOrNull() ?: run {
            ModuleLog.warn("$FEATURE: NetworkSpeedView#setNetworkSpeed(String,String) is gone")
            return 0
        }
        val ok = HookRuntime.hook(method, "$FEATURE/NetworkSpeedView#setNetworkSpeed") { chain ->
            val args = chain.args.toMutableList()
            val unit = args.getOrNull(1) as? String
            if (unit == null) {
                chain.proceed()
            } else {
                var cleaned: String = unit
                UNIT_CHARS.forEach { cleaned = cleaned.replace(it, "") }
                args[1] = cleaned
                chain.proceed(args.toTypedArray())
            }
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ 更新间隔

    private fun installUpdateInterval(loader: ClassLoader, config: Config): Int {
        if (config.updateIntervalMs == DEFAULT_UPDATE_INTERVAL_MS) return 0
        val controller = Reflect.loadClass(loader, CONTROLLER) ?: return 0
        // R8 merges UI and background handlers into $5 and strips MemberClasses.
        val bg = Reflect.findField(controller, "mBgHandler") ?: return 0
        if (!Handler::class.java.isAssignableFrom(bg.type)) return 0
        val handle = Reflect.findMethod(bg.type, "handleMessage", Message::class.java) ?: return 0
        val owner = Reflect.findField(bg.type, "this\$0") ?: return 0
        val hidden = Reflect.findField(controller, "mIsStatusBarHidden") ?: return 0
        return if (HookRuntime.hookAfter(handle, "$FEATURE/backgroundSample") { chain, result ->
                val handler = chain.thisObject as? Handler
                val message = chain.args[0] as? Message
                val host = owner.get(handler)
                if (handler != null && host != null && NetworkSpeedSchedule.shouldReschedule(
                        message?.what ?: -1, bg.get(host) === handler, hidden.getBoolean(host),
                        handler.hasMessages(NetworkSpeedSchedule.SAMPLE))) {
                    handler.removeMessages(NetworkSpeedSchedule.SAMPLE)
                    handler.sendEmptyMessageDelayed(NetworkSpeedSchedule.SAMPLE, config.updateIntervalMs)
                }
                result
            }) 1 else 0
    }

    // ------------------------------------------------------------------ 设置快照

    /** 一次算好的设置。拦截体里只读这个对象，不再碰设置代理。 */
    private class Config(
        val style: Int,
        val icon: Int,
        val hide: Boolean,
        val hideAll: Boolean,
        val swap: Boolean,
        val lowLevel: Long,
        val unitSuffix: String,
        val fontStyle: Int,
        val fontSize: Int,
        val fontSizeEnabled: Boolean,
        val align: Int,
        val fixedWidth: Int,
        val lineSpacing: Float,
        val leftMargin: Float,
        val rightMargin: Float,
        val verticalOffset: Int,
        val updateIntervalMs: Long,
    ) {
        val text = NetworkSpeedText.Options(style, icon, hide, hideAll, swap, lowLevel, unitSuffix)
        companion object {
            fun of(settings: HookSettings): Config = Config(
                style = settings.number(KEY_STYLE, 0, 0, 4),
                icon = settings.number(KEY_ICON, 2, 1, 6),
                hide = settings.isOn(OPT_HIDE),
                hideAll = settings.isOn(OPT_HIDE_ALL),
                swap = settings.isOn(OPT_SWAP),
                lowLevel = settings.number(KEY_HIDE_SLOW, 64, 0, Int.MAX_VALUE).toLong() * 1024L,
                unitSuffix = if (settings.isOn(OPT_SEC_UNIT)) "" else "B/s",
                fontStyle = settings.number(KEY_FONT_STYLE, 0, 0, 2),
                fontSize = settings.number(KEY_FONT_SIZE, 13, 0, 200),
                fontSizeEnabled = settings.isOn(OPT_FONT_SIZE_ENABLE),
                align = settings.number(KEY_ALIGN, 1, 1, 4),
                fixedWidth = settings.number(KEY_FIXED_WIDTH, 10, 0, 1000),
                lineSpacing = settings.number(KEY_SPACING_MARGIN, 16, 0, 200) * 0.05f,
                leftMargin = settings.number(KEY_LEFT_MARGIN, 0, 0, 200).toFloat(),
                rightMargin = settings.number(KEY_RIGHT_MARGIN, 0, 0, 200).toFloat(),
                verticalOffset = settings.number(KEY_VERTICAL_OFFSET, 40, 0, 800),
                updateIntervalMs = settings.number(KEY_UPDATE_SPACING, 40, 10, 100) * 100L,
            )
        }
    }

    private const val STYLE_DEFAULT = 0
    private const val STYLE_VALUE_UNIT_SINGLE = 1
    private const val STYLE_VALUE_UNIT_TWO_LINE = 2
    private const val STYLE_TX_RX_SINGLE = 3
    private const val STYLE_TX_RX_TWO_LINE = 4

    private const val DEFAULT_FIXED_WIDTH = 10
    private const val DEFAULT_VERTICAL_OFFSET = 40
    private const val DEFAULT_UPDATE_INTERVAL_MS = 4000L
}
