/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * StatusBarClockNew roles; local synchronous formatting and owned-style restoration.
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Typeface
import android.os.Looper
import android.text.method.TransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ClockSettings as Config
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

@TargetApi(29) // The version gate admits only the audited OS4 SystemUI.
internal class StatusBarClock private constructor(settings: HookSettings) {
    private data class Style(val bold: Boolean, val size: Int, val left: Int, val right: Int, val vertical: Float, val defaultSize: Int) {
        val changed get() = bold || size != defaultSize || left != 0 || right != 0 || vertical != 0f
    }
    private val styles = Config.roles.associateWith { role ->
        val vertical = settings.number(role.key("vertical_offset"), if (role == Config.status) 12 else 0)
        Style(settings.isOn(role.key("bold")), settings.number(role.key("size"), role.defaultSize, role.minSize, role.maxSize),
            settings.number(role.key("left_margin"), 0, 0, role.maxMargin), settings.number(role.key("right_margin"), 0, 0, role.maxMargin),
            (if (role == Config.status) vertical.coerceIn(0, 24) - 12 else vertical.coerceIn(-12, 12)) / 2f, role.defaultSize)
    }
    private val formats = ClockFormatPolicy(Config.choice(settings.string(Config.STYLE), 2),
        Config.choice(settings.string(Config.SYNC), 1) == 1, settings.isOn(Config.HIDE_PAD),
        settings.string("${Config.FEATURE}.editor_s"), settings.string("${Config.FEATURE}.editor_b"),
        settings.string("${Config.FEATURE}.editor_n"), settings.string("${Config.FEATURE}.editor_p"))
    private val alignment = Config.choice(settings.string(Config.ALIGN), 2)
    private val spacing = settings.number(Config.SPACING, 16, 14, 32) / 20f
    private val width = settings.number(Config.WIDTH, 30, 30, 120)
    private val bindings = WeakHashMap<TextView, WeakReference<Binding>>()
    private val ticker = ClockSecondsTicker { view -> bindings[view]?.get()?.refresh(format = true) }
    private val calendarMethods = mutableMapOf<Class<*>, CalendarMethods>()
    private val warnedPatterns = mutableSetOf<String>()

    private data class CalendarMethods(val read: Method, val write: Method, val format: Method)
    private data class Lines(val single: Boolean, val min: Int, val max: Int, val minHeight: Int, val maxHeight: Int,
        val scroll: Boolean, val transform: TransformationMethod?)

    private inner class Binding(val view: TextView, val name: String, val role: Config.Role) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val size = MobileTypeOwnedValue<Float>()
        private val face = MobileTypeOwnedValue<Typeface?>()
        private val left = MobileTypeOwnedValue<Int>()
        private val right = MobileTypeOwnedValue<Int>()
        private val y = MobileTypeOwnedValue<Float>()
        private val fixedWidth = MobileTypeOwnedValue<Int>()
        private val align = MobileTypeOwnedValue<Int>()
        private val lineSpacing = MobileTypeOwnedValue<Pair<Float, Float>>()
        private val lines = MobileTypeOwnedValue<Lines>()
        private val visibility = MobileTypeOwnedValue<Int>()
        private val text = ClockTextOverride()
        private var observer: ViewTreeObserver? = null
        private var failed = false
        private var nativeTimeWrite = false

        private fun protect(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                failed = true
                ModuleLog.error("${Config.FEATURE}/$name: native clock restored", failure)
                SafeModeRuntime.trip("Hook failed: clock style (${failure.javaClass.simpleName})")
                runCatching { restore() }
            }
        }

        override fun onViewAttachedToWindow(v: View) = protect {
            if (observer != null) return@protect
            observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            refresh(format = true)
            if (formats.needsSeconds(name)) ticker.attach(view)
        }

        override fun onViewDetachedFromWindow(v: View) = protect {
            ticker.detach(view)
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            restore()
        }

        override fun onPreDraw(): Boolean { refresh(format = false); return true }

        // Let native updateTime compare against its own last text and preserve native
        // content descriptions, demo mode, and notification-header whitespace handling.
        fun beforeTime() = protect { nativeTimeWrite = true; restoreText() }
        fun afterTime() { nativeTimeWrite = false; refresh(format = true) }

        fun refresh(format: Boolean) = protect {
            if (nativeTimeWrite) return@protect
            if (failed || SafeModeRuntime.blocked) { restore(); return@protect }
            val controller = Reflect.readField(view, "mMiuiStatusBarClockController") ?: return@protect
            if (Reflect.readField(controller, "mDemoMode") == true) { restore(); return@protect }
            applyStyle()
            if (format && !(name == "pad_clock" && formats.hidePad)) {
                val pattern = formats.pattern(name, Reflect.readField(controller, "mIs24") as? Boolean ?: true)
                if (pattern == null) restoreText() else {
                    val rendered = render(view.context, controller, pattern)
                    if (rendered == null) restoreText() else {
                        val next = text.apply(view.text, rendered)
                        if (view.text.toString() != next.toString()) view.text = next
                    }
                }
            }
        }

        private fun lineState() = Lines(view.isSingleLine, view.minLines, view.maxLines, view.minHeight, view.maxHeight,
            view.isHorizontallyScrollable, view.transformationMethod)

        private fun setLines(next: Lines) {
            if (lineState() == next) return
            view.isSingleLine = next.single
            if (next.min >= 0) view.minLines = next.min else view.minHeight = next.minHeight
            if (next.max >= 0) view.maxLines = next.max else view.maxHeight = next.maxHeight
            view.setHorizontallyScrolling(next.scroll)
            view.transformationMethod = next.transform
        }

        private fun setSpacing(next: Pair<Float, Float>) {
            if (view.lineSpacingExtra != next.first || view.lineSpacingMultiplier != next.second) view.setLineSpacing(next.first, next.second)
        }

        private fun applyStyle() {
            val style = styles.getValue(role)
            val density = view.resources.displayMetrics.density
            val newSize = if (style.size != style.defaultSize) size.apply(view.textSize) { style.size * density } else size.restore(view.textSize)
            if (view.textSize != newSize) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, newSize)
            val newFace = if (style.bold) face.apply(view.typeface) { Typeface.create(it, Typeface.BOLD) } else face.restore(view.typeface)
            if (view.typeface != newFace) view.typeface = newFace
            val l = if (style.left != 0) left.apply(view.paddingLeft) { (style.left * density).roundToInt() } else left.restore(view.paddingLeft)
            val r = if (style.right != 0) right.apply(view.paddingRight) { (style.right * density).roundToInt() } else right.restore(view.paddingRight)
            setPadding(l, r)
            val newY = if (style.vertical != 0f) y.apply(view.translationY) { it + style.vertical * density } else y.restore(view.translationY)
            if (view.translationY != newY) view.translationY = newY
            view.layoutParams?.let { params ->
                val target = if (name == "clock" && width > 30) fixedWidth.apply(params.width) { (width * density).roundToInt() }
                    else fixedWidth.restore(params.width)
                if (params.width != target) { params.width = target; view.layoutParams = params }
            }
            if (name == "clock" && formats.style in 1..2) {
                setLines(lines.apply(lineState()) { Lines(false, 1, 2, -1, -1, false, null) })
                val target = when (alignment) { 1 -> View.TEXT_ALIGNMENT_CENTER; 2 -> View.TEXT_ALIGNMENT_VIEW_END; else -> View.TEXT_ALIGNMENT_VIEW_START }
                val aligned = align.apply(view.textAlignment) { target }
                if (view.textAlignment != aligned) view.textAlignment = aligned
                setSpacing(lineSpacing.apply(view.lineSpacingExtra to view.lineSpacingMultiplier) { 0f to spacing })
            } else restoreLines()
            val visible = if (name == "pad_clock" && formats.hidePad) visibility.apply(view.visibility) { View.GONE }
                else visibility.restore(view.visibility)
            if (view.visibility != visible) view.visibility = visible
        }

        private fun setPadding(l: Int, r: Int) {
            if (view.paddingLeft != l || view.paddingRight != r) view.setPadding(l, view.paddingTop, r, view.paddingBottom)
        }

        private fun restoreLines() {
            setLines(lines.restore(lineState()))
            val old = align.restore(view.textAlignment)
            if (view.textAlignment != old) view.textAlignment = old
            setSpacing(lineSpacing.restore(view.lineSpacingExtra to view.lineSpacingMultiplier))
        }

        private fun restoreText() {
            val old = text.restore(view.text)
            if (view.text.toString() != old.toString()) view.text = old
        }

        private fun restore() {
            restoreText()
            val oldSize = size.restore(view.textSize)
            if (view.textSize != oldSize) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, oldSize)
            val oldFace = face.restore(view.typeface)
            if (view.typeface != oldFace) view.typeface = oldFace
            setPadding(left.restore(view.paddingLeft), right.restore(view.paddingRight))
            val oldY = y.restore(view.translationY)
            if (view.translationY != oldY) view.translationY = oldY
            view.layoutParams?.let { params ->
                val old = fixedWidth.restore(params.width)
                if (params.width != old) { params.width = old; view.layoutParams = params }
            }
            restoreLines()
            val oldVisible = visibility.restore(view.visibility)
            if (view.visibility != oldVisible) view.visibility = oldVisible
        }
    }

    private fun render(context: Context, controller: Any, pattern: String): String? = runCatching {
        val calendar = Reflect.readField(controller, "mCalendar") ?: return null
        val methods = calendarMethods.getOrPut(calendar.javaClass) {
            CalendarMethods(calendar.javaClass.getMethod("getTimeInMillis"),
                calendar.javaClass.getMethod("setTimeInMillis", Long::class.javaPrimitiveType),
                calendar.javaClass.getMethod("format", Context::class.java, CharSequence::class.java))
        }
        ClockCalendarScope.atTime({ methods.read.invoke(calendar) as Long }, { methods.write.invoke(calendar, it) },
            System.currentTimeMillis()) { methods.format.invoke(calendar, context, pattern) as String }
    }.getOrElse { failure ->
        // Invalid user formats are compatibility failures, not grounds to disable all hooks.
        if (warnedPatterns.add(pattern)) ModuleLog.warn("${Config.FEATURE}: format skipped (${failure.javaClass.simpleName})")
        null
    }

    private fun binding(view: TextView): Binding? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        bindings[view]?.get()?.let { return it }
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() ?: return null
        val role = Config.role(name) ?: return null
        if (view.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode != 202602260L) return null
        return Binding(view, name, role).also {
            bindings[view] = WeakReference(it)
            view.addOnAttachStateChangeListener(it)
            if (view.isAttachedToWindow) it.onViewAttachedToWindow(view)
        }
    }

    companion object {
        fun install(loader: ClassLoader, settings: HookSettings): Int {
            val controller = StatusBarClock(settings)
            if (!controller.formats.changed && controller.styles.values.none { it.changed } && controller.width == 30) return 0
            val clock = Reflect.loadClass(loader, "com.android.systemui.statusbar.views.MiuiClock") ?: return 0
            val update = Reflect.findMethod(clock, "updateTime") ?: return 0
            val hostController = Reflect.loadClass(loader, "com.android.systemui.statusbar.policy.MiuiStatusBarClockController") ?: return 0
            // MT found these three direct callers; optimized code must still enter our hook.
            val callers = listOf(Reflect.findMethod(clock, "onAttachedToWindow"),
                Reflect.findMethod(clock, "setClockMode", Int::class.javaPrimitiveType!!),
                Reflect.findMethod(hostController, "updateTime\$1"))
            if (callers.any { it == null }) return 0
            if (!callers.map { HookRuntime.deoptimize(it!!, "${Config.FEATURE}/caller/${it.name}") }.all { it }) return 0
            var count = 0
            if (HookRuntime.hook(update, "${Config.FEATURE}/MiuiClock#updateTime") { chain ->
                val view = chain.thisObject as? TextView
                val state = view?.let(controller::binding)
                state?.beforeTime()
                try { chain.proceed() } finally { state?.afterTime() }
            }) count++
            if (HookRuntime.hookAfter(Reflect.findMethod(clock, "onAttachedToWindow"), "${Config.FEATURE}/attach") { chain, original ->
                    (chain.thisObject as? TextView)?.let(controller::binding)
                    original
                }) count++
            if (controller.formats.hidePad && HookRuntime.hookAfter(Reflect.findMethod(clock, "updateClockVisibility"), "${Config.FEATURE}/visibility") { chain, original ->
                    (chain.thisObject as? TextView)?.let(controller::binding)?.refresh(format = false)
                    original
                }) count++
            return count
        }
    }
}
