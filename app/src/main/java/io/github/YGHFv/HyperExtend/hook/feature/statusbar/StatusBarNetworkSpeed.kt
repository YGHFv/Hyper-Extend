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
import android.content.Context
import android.os.Looper
import android.net.TrafficStats
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod
import java.lang.reflect.Modifier
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

    private class Access(loader: ClassLoader, private val settings: HookSettings, sampleTraffic: Boolean) {
        val methods = NetworkSpeedTargets.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val counters = if (sampleTraffic) NetworkSpeedTargets.counters.associateWith {
            requireNotNull(NotificationHooks.resolve(loader, it))
        } else emptyMap()
        val fields = NetworkSpeedTargets.fields.associate { (owner, name, expected) ->
            (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        @Volatile var ready = false
        @Volatile private var version: Long? = null
        val ownedUnits = WeakHashMap<TextView, Int>()
        fun enabled(context: Context, ui: Boolean = true) = runCatching {
            if (!ready || SafeModeRuntime.blocked || !settings.isOn(FEATURE) ||
                ui && Looper.myLooper() != Looper.getMainLooper()) return@runCatching false
            val current = version ?: context.packageManager.getPackageInfo("com.android.systemui", 0)
                .compatibleVersionCode.also { version = it }
            current == 202602260L
        }.getOrDefault(false)
        fun method(owner: String, name: String, returns: String = "void", vararg parameters: String) =
            methods.getValue(SystemUiMethod(owner, name, returns, parameters.toList()))
        fun field(owner: String, name: String) = fields.getValue(owner to name)
    }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val config = Config.of(settings)
        val access = Access(loader, settings, config.style != STYLE_DEFAULT || config.hide)
        if (!access.methods.values.all { HookRuntime.deoptimize(it, "$FEATURE/caller") }) return 0
        var installed = 0
        installed += installUpdateText(access, config)
        installed += installViewStyle(access, config)
        installed += installUnitSuffix(access, settings, config)
        installed += installUpdateInterval(access, config)
        val expected = 6 +
            (if (config.updateIntervalMs != DEFAULT_UPDATE_INTERVAL_MS) 1 else 0)
        access.ready = installed == expected
        return if (access.ready) installed else 0
    }

    // ------------------------------------------------------------------ 速率文本

    private fun installUpdateText(access: Access, config: Config): Int {
        val updateText = access.method(CONTROLLER, "updateText", "void", "[Ljava.lang.String;")
        val ok = HookRuntime.hook(updateText, "$FEATURE/NetworkSpeedController#updateText") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val context = access.field(CONTROLLER, "mContext").get(owner) as? Context ?: return@hook chain.proceed()
            if (!access.enabled(context) || access.field(CONTROLLER, "mIsStatusBarHidden").getBoolean(owner)) {
                synchronized(samplers) { samplers.remove(owner) }
                return@hook chain.proceed()
            }
            if (config.style == STYLE_DEFAULT && !config.hide) return@hook chain.proceed()
            val speeds = synchronized(samplers) {
                val sampler = samplers.getOrPut(owner) { NetworkSpeedSampler() }
                runCatching {
                    val sent = TrafficStats.getTotalTxBytes()
                    val received = TrafficStats.getTotalRxBytes()
                    val loopbackSent = access.counters.getValue(NetworkSpeedTargets.loopbackTx).invoke(null, "lo") as Long
                    val loopbackReceived = access.counters.getValue(NetworkSpeedTargets.loopbackRx).invoke(null, "lo") as Long
                    sampler.sample(System.nanoTime(), sent, received, loopbackSent, loopbackReceived)
                        .takeIf { sampler.valid }
                }.getOrElse {
                    samplers.remove(owner)
                    null
                }
            } ?: return@hook chain.proceed()
            val replacement = NetworkSpeedText.render(speeds.first, speeds.second, config.text)
            if (replacement == null) chain.proceed() else chain.proceed(arrayOf<Any?>(replacement))
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ 视图样式

    private fun installViewStyle(access: Access, config: Config): Int {
        var installed = 0
        for (name in VIEW_RESTYLE_METHODS) {
            val method = if (name == "onMiuiThemeChanged") access.method(SPEED_VIEW, name, "void", "boolean")
                else access.method(SPEED_VIEW, name)
            val ok = HookRuntime.hookAfter(method, "$FEATURE/NetworkSpeedView#$name") { chain, original ->
                val view = chain.thisObject as? View ?: return@hookAfter original
                if (access.enabled(view.context)) restyle(view, config, access)
                original
            }
            if (ok) installed++
        }
        return installed
    }

    private fun restyle(meter: View, config: Config, access: Access) {
        val number = access.field(SPEED_VIEW, "mNetworkSpeedNumberText").get(meter) as? TextView ?: return
        val unit = access.field(SPEED_VIEW, "mNetworkSpeedUnitText").get(meter) as? TextView

        if (config.fixedWidth > DEFAULT_FIXED_WIDTH) {
            val width = (meter.resources.displayMetrics.density * config.fixedWidth).toInt()
            val params = meter.layoutParams ?: ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT)
            params.width = width
            meter.layoutParams = params
        }

        val single = arrayOf(number, unit)
        if (config.style != STYLE_DEFAULT) {
            // Unit visibility follows actual two-slot data, including native fallback.
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
        if (config.align == 1) return
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
    private fun installUnitSuffix(access: Access, settings: HookSettings, config: Config): Int {
        val method = access.method(SPEED_VIEW, "setNetworkSpeed", "void", "java.lang.String", "java.lang.String")
        val ok = HookRuntime.hook(method, "$FEATURE/NetworkSpeedView#setNetworkSpeed") { chain ->
            val view = chain.thisObject as? View ?: return@hook chain.proceed()
            val enabled = access.enabled(view.context)
            val args = chain.args.toMutableList()
            val unit = args.getOrNull(1) as? String
            if (enabled && settings.isOn(OPT_SEC_UNIT) && unit != null) {
                var cleaned: String = unit
                UNIT_CHARS.forEach { cleaned = cleaned.replace(it, "") }
                args[1] = cleaned
            }
            val original = chain.proceed(args.toTypedArray())
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val label = access.field(SPEED_VIEW, "mNetworkSpeedUnitText").get(view) as? TextView
                if (label != null) {
                    if (enabled && NetworkSpeedText.hideUnit(config.style, args[1] as? String)) {
                        if (label !in access.ownedUnits) access.ownedUnits[label] = label.visibility
                        label.visibility = View.GONE
                    } else access.ownedUnits.remove(label)?.let {
                        if (label.visibility == View.GONE) label.visibility = it
                    }
                }
            }
            original
        }
        return if (ok) 1 else 0
    }

    // ------------------------------------------------------------------ 更新间隔

    private fun installUpdateInterval(access: Access, config: Config): Int {
        if (config.updateIntervalMs == DEFAULT_UPDATE_INTERVAL_MS) return 0
        // R8 merges UI and background handlers into $5 and strips MemberClasses.
        val bg = access.field(CONTROLLER, "mBgHandler")
        val handle = access.method("$CONTROLLER\$5", "handleMessage", "void", "android.os.Message")
        val owner = access.field("$CONTROLLER\$5", "this\$0")
        val hidden = access.field(CONTROLLER, "mIsStatusBarHidden")
        return if (HookRuntime.hookAfter(handle, "$FEATURE/backgroundSample") { chain, result ->
                val handler = chain.thisObject as? Handler ?: return@hookAfter result
                val message = chain.args[0] as? Message
                val host = owner.get(handler) ?: return@hookAfter result
                val context = access.field(CONTROLLER, "mContext").get(host) as? Context ?: return@hookAfter result
                if (access.enabled(context, false) && Looper.myLooper() == handler.looper && NetworkSpeedSchedule.shouldReschedule(
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
                lowLevel = settings.number(KEY_HIDE_SLOW, 64, 1, 2048).toLong() * 1024L,
                unitSuffix = if (settings.isOn(OPT_SEC_UNIT)) "" else "B/s",
                fontStyle = settings.number(KEY_FONT_STYLE, 0, 0, 2),
                fontSize = settings.number(KEY_FONT_SIZE, 13, 8, 40),
                fontSizeEnabled = settings.isOn(OPT_FONT_SIZE_ENABLE),
                align = settings.number(KEY_ALIGN, 1, 1, 4),
                fixedWidth = settings.number(KEY_FIXED_WIDTH, 10, 10, 150),
                lineSpacing = NetworkSpeedText.lineSpacing(settings.number(KEY_SPACING_MARGIN, 16, 14, 22)),
                leftMargin = settings.number(KEY_LEFT_MARGIN, 0, 0, 16).toFloat(),
                rightMargin = settings.number(KEY_RIGHT_MARGIN, 0, 0, 16).toFloat(),
                verticalOffset = settings.number(KEY_VERTICAL_OFFSET, 40, 0, 80),
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
