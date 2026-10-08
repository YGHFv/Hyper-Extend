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

import android.content.Context
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
import java.util.Locale

/**
 * 「网速指示器」。作用域：com.android.systemui。
 *
 * ## 为什么自己算速率
 *
 * 系统那个指示器只显示一个总速率，样式也是固定的；要「上下行分开」「双排」「隐藏慢速」，
 * 就必须自己拿到两个方向的速度。做法是拦下控制器的刷新入口
 * （`NetworkSpeedController#updateText`），把算好的字符串塞进它的参数 ——
 * 之后显示、布局、动画全都还是走系统那一套，只是内容换成了我们的。
 *
 * 速率本身用 `TrafficStats` 的累计字节数做差：两次数值之间隔了多久，就除以多久。
 * 两条纪律：**间隔太短不采样**（刷新回调可能连着来，差值会被噪声淹没）、
 * **间隔太长按上限截断**（设备刚息屏又亮起时，用一个几分钟的间隔去除，会得到一个荒谬的小值）。
 *
 * ## 与参考项目的差异
 *
 * - 参考项目逐网卡累加流量（过滤掉虚拟网卡与回环），这里直接用 `TrafficStats` 的全局计数。
 *   多算了虚拟网卡那部分，换来的是不需要 `NetworkInterface` 枚举与逐个反射调用 ——
 *   在一个每两秒跑一次的路径上，这个取舍是值得的。
 * - 「网速更新间隔」不靠消息 id 硬编码：那个 id（参考项目里的 200001）是宿主内部常量，
 *   我们改成**自校准** —— 哪条消息在处理过程中触发了 `updateText`，那条就是该重排的消息。
 */
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

    private const val KB = 1024.0
    private const val MB = KB * 1024.0
    private const val GB = MB * 1024.0

    /** 两次采样之间的最短间隔：比它更短的差值全是噪声。 */
    private const val MIN_SAMPLE_NANOS = 150_000_000L

    /** 采样间隔的上限：息屏再亮起时用它兜住，免得算出一个荒谬的小速率。 */
    private const val MAX_SAMPLE_NANOS = 10_000_000_000L

    /** 单位后缀里那几个字符。开了「隐藏 *b/s 单位」就按它们裁掉。 */
    private val UNIT_CHARS = listOf("/", "B", "s", "'", "วิ")

    /** 速度视图里重新排版的几个入口（字号变化、主题变化、重新 inflate 都会重排）。 */
    private val VIEW_RESTYLE_METHODS =
        arrayOf("onFinishInflate", "onDensityOrFontScaleChanged", "onMiuiThemeChanged", "updateResources\$15")

    private var lastSampleNanos = 0L
    private var lastTxBytes = 0L
    private var lastRxBytes = 0L
    private var txSpeed = 0L
    private var rxSpeed = 0L

    /** 本次 `handleMessage` 里有没有走到 `updateText` —— 见 [installUpdateInterval]。 */
    @Volatile
    private var updateTextSeen = false

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
        val updateText = Reflect.findMethods(controller, "updateText", 1).firstOrNull() ?: run {
            ModuleLog.warn("$FEATURE: NetworkSpeedController#updateText(String[]) is gone")
            return 0
        }
        val ok = HookRuntime.hook(updateText, "$FEATURE/NetworkSpeedController#updateText") { chain ->
            // 只是「顺带算一次」，context 取不到就原样放行 —— 没有它也能算，只是算不出单位文案。
            val context = Reflect.readField(chain.thisObject, "mContext") as? Context
            updateTextSeen = true
            val replacement = buildSpeedText(context, config)
            if (replacement == null) chain.proceed() else chain.proceed(arrayOf<Any?>(replacement))
        }
        return if (ok) 1 else 0
    }

    /**
     * 算出要交给宿主的那两个字符串，返回 null 表示「这次不改」。
     *
     * 返回 null 只在一种情况下发生：样式是「默认」且这次不算慢速 —— 那时系统自己那套
     * （一个速率、固定样式）就是用户要的，我们只负责在需要隐藏的时候把它抹掉。
     */
    @Synchronized
    private fun buildSpeedText(context: Context?, config: Config): Array<String>? {
        val (tx, rx) = sample()
        val txLow = tx < config.lowLevel
        val rxLow = rx < config.lowLevel

        if (config.style == STYLE_DEFAULT) {
            val allLow = config.hide && (tx + rx) < config.lowLevel
            return if (allLow) arrayOf("", "") else null
        }

        val txText = if (config.hide && !config.hideAll && txLow) {
            ""
        } else {
            withArrow(format(tx, config), arrow(config.icon, up = true, low = txLow), config.swap)
        }
        val rxText = if (config.hide && !config.hideAll && rxLow) {
            ""
        } else {
            withArrow(format(rx, config), arrow(config.icon, up = false, low = rxLow), config.swap)
        }
        val total = format(tx + rx, config)
        val allLow = config.hide && config.hideAll && txLow && rxLow

        return when (config.style) {
            // 值和单位单行 / 双排：都只显示总速率（双排的那个换行在 format 里）。
            STYLE_VALUE_UNIT_SINGLE, STYLE_VALUE_UNIT_TWO_LINE ->
                arrayOf(if (config.hide && (tx + rx) < config.lowLevel) "" else total)
            STYLE_TX_RX_SINGLE ->
                arrayOf(if (allLow) "" else if (rxText.isNotEmpty()) "$txText $rxText" else txText)
            else -> arrayOf(if (allLow) "" else "$txText\n$rxText")
        }
    }

    /**
     * 取一次速率（字节/秒）。
     *
     * 同步是必需的：`updateText` 与采样状态是一对多的关系（主线程、后台 handler 都可能走到），
     * 两次采样交叉执行会得到「上一次的差值算在上一次的间隔上」这种错位。
     */
    @Synchronized
    private fun sample(): Pair<Long, Long> {
        val now = System.nanoTime()
        val tx = TrafficStats.getTotalTxBytes()
        val rx = TrafficStats.getTotalRxBytes()

        if (lastSampleNanos == 0L) {
            lastSampleNanos = now
            lastTxBytes = tx
            lastRxBytes = rx
            return 0L to 0L
        }

        var interval = now - lastSampleNanos
        if (interval < MIN_SAMPLE_NANOS) return txSpeed to rxSpeed
        if (interval > MAX_SAMPLE_NANOS) interval = MAX_SAMPLE_NANOS

        lastSampleNanos = now
        val deltaTx = (tx - lastTxBytes).coerceAtLeast(0L)
        val deltaRx = (rx - lastRxBytes).coerceAtLeast(0L)
        lastTxBytes = tx
        lastRxBytes = rx

        val seconds = interval / 1_000_000_000.0
        txSpeed = (deltaTx / seconds).toLong()
        rxSpeed = (deltaRx / seconds).toLong()
        return txSpeed to rxSpeed
    }

    private fun withArrow(value: String, arrow: String, swap: Boolean): String =
        if (swap) "$arrow$value" else "$value$arrow"

    /**
     * 指示器图标那一档（`1` 无图标、`2` 上下行箭头……）。
     *
     * 低速时用空心那一个 —— 与参考项目一致：图标本身就是「快 / 慢」的第二重表达。
     */
    private fun arrow(icon: Int, up: Boolean, low: Boolean): String = when (icon) {
        2 -> if (up) (if (low) "△" else "▲") else (if (low) "▽" else "▼")
        3 -> if (up) (if (low) " ▵" else " ▴") else (if (low) " ▿" else " ▾")
        4 -> if (up) (if (low) " ☖" else " ☗") else (if (low) " ⛉" else " ⛊")
        5 -> if (up) "↑" else "↓"
        6 -> if (up) "⇧" else "⇩"
        else -> ""
    }

    /** 字节数 → 显示文案。双排样式（2）把单位换到第二行。 */
    private fun format(bytes: Long, config: Config): String {
        val value: Double
        val unit: Char
        when {
            bytes >= GB -> { value = bytes / GB; unit = 'G' }
            bytes >= MB -> { value = bytes / MB; unit = 'M' }
            else -> { value = bytes / KB; unit = 'K' }
        }
        val number = if (value < 100.0) String.format(Locale.US, "%.1f", value) else String.format(Locale.US, "%.0f", value)
        return if (config.style == STYLE_VALUE_UNIT_TWO_LINE) {
            "$number\n$unit${config.unitSuffix}"
        } else {
            "$number$unit${config.unitSuffix}"
        }
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
            // 自定义样式下，数值与单位都在同一个字符串里（见 format），原来的单位视图必须让位。
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

    /**
     * 「网速更新间隔」。
     *
     * 宿主的刷新节奏由它自己往后台 handler 上排队的一串消息决定，消息 id 是内部常量
     * （参考项目写死成 200001）。这里不写死：谁在处理过程中触发了 `updateText`，
     * 谁就是那张「该重排的消息」—— 拦下处理完的那一刻，把它按新的间隔重排一次。
     */
    private fun installUpdateInterval(loader: ClassLoader, config: Config): Int {
        if (config.updateIntervalMs == DEFAULT_UPDATE_INTERVAL_MS) return 0
        val controller = Reflect.loadClass(loader, CONTROLLER) ?: return 0
        val handlers = controller.declaredClasses.filter { android.os.Handler::class.java.isAssignableFrom(it) }
        if (handlers.isEmpty()) {
            ModuleLog.warn("$FEATURE: NetworkSpeedController has no Handler subclass — interval skipped")
            return 0
        }
        var installed = 0
        for (handler in handlers) {
            val handle = Reflect.findMethods(handler, "handleMessage", 1).firstOrNull() ?: continue
            val ok = HookRuntime.hook(handle, "$FEATURE/${handler.simpleName}#handleMessage") { chain ->
                updateTextSeen = false
                val result = chain.proceed()
                if (updateTextSeen) reschedule(chain.thisObject, chain.args.getOrNull(0), config)
                result
            }
            if (ok) installed++
        }
        return installed
    }

    private fun reschedule(handler: Any?, message: Any?, config: Config) {
        if (handler == null || message == null) return
        val what = (Reflect.readField(message, "what") as? Int) ?: return
        Reflect.callWith(handler, "removeMessages", what)
        Reflect.callWith(handler, "sendEmptyMessageDelayed", what, config.updateIntervalMs)
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
                updateIntervalMs = settings.number(KEY_UPDATE_SPACING, 40, 0, 1000) * 100L,
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
