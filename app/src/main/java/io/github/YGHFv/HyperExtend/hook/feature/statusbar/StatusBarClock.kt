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
 * 功能来源：西米露 / HyperCeiler · StatusBarClockNew（AGPL-3.0）。
 */

package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 「时钟指示器」。作用域：com.android.systemui。
 *
 * ## 为什么认视图资源名而不认类
 *
 * 状态栏时钟、通知中心大时钟、迷你时钟、Pad 日期时钟在 OS4 上是**同一个类**
 * （`MiuiClock`）的不同实例，靠构造参数或字段根本分不出来。它们的资源 id 名
 * （`clock` / `big_time` / `date_time` / `pad_clock`）才是宿主自己用来区分的口径，
 * 参考项目也是这么认的。
 *
 * ## 为什么格式要绕到控制器上取日历
 *
 * 时钟文案不是用 `SimpleDateFormat` 拼的，而是宿主那个 MIUI 日历
 * （`miuix.pickerwidget.date.Calendar`）自己的 `format(context, out, pattern)` ——
 * 它认得 `E`（星期）、`M/d` 这些 MIUI 自己的语法。控制器上那个 `mCalendar` 实例
 * 就是它，所以格式化走它，写法与宿主完全一致。取不到就安静跳过：宁可保持系统写法，
 * 也不要另造一套格式（那会和用户在别处看到的 MIUI 格式示例对不上）。
 *
 * ## 只做状态栏时钟的样式
 *
 * 字号、边距、加粗只作用在资源名 `clock`（状态栏那个）上。通知中心的大时钟默认 50dp，
 * 把状态栏那套 12dp 套上去会直接把它压没 —— 参考项目为每一个时钟各留了一组数值，
 * 这里只做用户最常改的那一个，其余保持系统原样。
 */
internal object StatusBarClock {

    private const val FEATURE = "status_bar_clock"

    private const val OPT_BOLD = "status_bar_clock.bold"

    private const val KEY_SIZE = "status_bar_clock.size"
    private const val KEY_LEFT = "status_bar_clock.left_margin"
    private const val KEY_RIGHT = "status_bar_clock.right_margin"
    private const val KEY_VERTICAL = "status_bar_clock.vertical_offset"

    private const val KEY_FORMAT_S = "status_bar_clock.editor_s"
    private const val KEY_FORMAT_B = "status_bar_clock.editor_b"
    private const val KEY_FORMAT_N = "status_bar_clock.editor_n"
    private const val KEY_FORMAT_P = "status_bar_clock.editor_p"

    private const val CLOCK_CLASS = "com.android.systemui.statusbar.views.MiuiClock"
    private const val STATUSBAR_CLOCK_NAME = "clock"

    private const val DEFAULT_SIZE = 12
    private const val DEFAULT_VERTICAL_OFFSET = 12

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val style = ClockStyle(
            bold = settings.isOn(OPT_BOLD),
            size = settings.number(KEY_SIZE, DEFAULT_SIZE, 0, 200),
            left = settings.number(KEY_LEFT, 0, 0, 500),
            right = settings.number(KEY_RIGHT, 0, 0, 500),
            vertical = settings.number(KEY_VERTICAL, DEFAULT_VERTICAL_OFFSET, 0, 500),
        )
        val formats = buildMap {
            put(STATUSBAR_CLOCK_NAME, settings.string(KEY_FORMAT_S))
            put("big_time", settings.string(KEY_FORMAT_B))
            put("date_time", settings.string(KEY_FORMAT_N))
            put("pad_clock", settings.string(KEY_FORMAT_P))
        }.filterValues { it.isNotBlank() }

        if (!style.changed && formats.isEmpty()) return 0

        val clock = Reflect.loadClass(loader, CLOCK_CLASS) ?: run {
            ModuleLog.warn("$FEATURE: $CLOCK_CLASS not found")
            return 0
        }
        val updateTime = Reflect.findMethods(clock, "updateTime", 0).firstOrNull() ?: run {
            ModuleLog.warn("$FEATURE: MiuiClock#updateTime() is gone")
            return 0
        }

        val ticker = ClockSecondsTicker { apply(it, style, formats) }
        var count = 0
        val ok = HookRuntime.hookAfter(updateTime, "$FEATURE/MiuiClock#updateTime") { chain, original ->
            apply(chain.thisObject, style, formats)
            original
        }
        if (ok) count++
        if (formats.values.any(ClockTickPolicy::needsSeconds)) {
            if (HookRuntime.hookAfter(Reflect.findMethod(clock, "onAttachedToWindow"), "$FEATURE/attach") { chain, original ->
                    (chain.thisObject as? TextView)?.let { view ->
                        val name = view.resources.getResourceEntryName(view.id)
                        if (formats[name]?.let(ClockTickPolicy::needsSeconds) == true) ticker.attach(view)
                    }
                    original
                }) count++
            if (HookRuntime.hookAfter(Reflect.findMethod(clock, "onDetachedFromWindow"), "$FEATURE/detach") { chain, original ->
                    (chain.thisObject as? TextView)?.let(ticker::detach)
                    original
                }) count++
        }
        return count
    }

    private fun apply(target: Any?, style: ClockStyle, formats: Map<String, String>) {
        val clock = target as? TextView ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            clock.post { apply(clock, style, formats) }
            return
        }
        val name = Reflect.attempt { clock.resources.getResourceEntryName(clock.id) } ?: return

        if (name == STATUSBAR_CLOCK_NAME && style.changed) applyStatusBarStyle(clock, style)
        formats[name]?.let { applyFormat(clock, it) }
    }

    private fun applyStatusBarStyle(clock: TextView, style: ClockStyle) {
        if (style.bold) clock.typeface = Typeface.DEFAULT_BOLD
        if (style.size != DEFAULT_SIZE) {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_DIP, style.size.toFloat())
        }
        val density = clock.resources.displayMetrics.density
        val top = if (style.vertical != DEFAULT_VERTICAL_OFFSET) {
            ((style.vertical - DEFAULT_VERTICAL_OFFSET) * 0.5f * density).toInt()
        } else {
            0
        }
        clock.setPaddingRelative(
            (style.left * density).toInt(),
            top,
            (style.right * density).toInt(),
            0,
        )
    }

    /**
     * 用控制器上的那个 MIUI 日历按用户给的模式重新排一遍时间，然后直接写回文本。
     *
     * 文案是**在宿主刚写完自己的那一份之后**盖上去的（这是 `hookAfter`），
     * 所以下一次 `updateTime` 到来时又会先被覆盖、再被我们盖回来 —— 每一跳都是完整的，
     * 不会出现「改了一半」的中间态。
     */
    private fun applyFormat(clock: TextView, pattern: String) {
        val controller = Reflect.readField(clock, "mMiuiStatusBarClockController") ?: return
        if (Reflect.readField(controller, "mDemoMode") == true) return
        val calendar = Reflect.readField(controller, "mCalendar") ?: return
        val format = Reflect.firstMethod(calendar.javaClass, "format") { it.parameterCount == 3 } ?: return
        Reflect.callWith(calendar, "setTimeInMillis", System.currentTimeMillis())

        val rendered = StringBuilder(128)
        val text = Reflect.attempt {
            format.isAccessible = true
            format.invoke(calendar, clock.context, rendered, pattern)
            rendered.toString()
        } ?: return

        // 视图操作必须在主线程。正常路径上 updateTime 本来就在主线程，
        // 万一不是，退回 post 而不是让宿主进程因为线程检查崩掉（TextViews setText 会）。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clock.text = text
        } else {
            clock.post { clock.text = text }
        }
    }
}
