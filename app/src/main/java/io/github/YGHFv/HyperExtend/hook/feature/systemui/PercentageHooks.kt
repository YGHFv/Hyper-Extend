/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.TargetApi
import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.PercentageSettings as Config
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(30)
internal class PercentageHooks private constructor(private val settings: HookSettings, hostLoader: ClassLoader) {
    private val bindings = WeakHashMap<SeekBar, WeakReference<Binding>>()
    private val windows = WeakHashMap<ViewGroup, WeakReference<Binding>>()
    private data class PanelSource(val owner: WeakReference<Any>, val column: WeakReference<Any>, val stream: Int)
    private val panelSources = WeakHashMap<SeekBar, PanelSource>()
    private var supported: Boolean? = null
    private val ready = AtomicBoolean(false)
    private val top = Config.top(settings.string(Config.TOP))
    private val backdrop = if (settings.isOn(Config.BLUR)) runCatching { OwnedBackdrop(hostLoader) }.getOrNull() else null

    private fun enabled(volume: Boolean) = settings.isOn(Config.FEATURE) &&
        Config.style(settings.string(Config.STYLE)) == 2 && settings.isOn(if (volume) Config.VOLUME else Config.BRIGHTNESS)

    private fun supported(bar: SeekBar, plugin: Boolean): Boolean = supported ?: runCatching {
        val pm = bar.context.packageManager
        pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L &&
            (!plugin || pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode == 183022200L)
    }.getOrDefault(false).also { supported = it }

    private fun start(bar: SeekBar, volume: Boolean, value: (Int) -> String?): Binding? {
        if (!enabled(volume) || SafeModeRuntime.blocked || !bar.isAttachedToWindow || !bar.isShown ||
            !bar.isEnabled || bar.display?.displayId != 0) return null
        bindings[bar]?.get()?.let { it.update(bar.progress); if (it.active) return it }
        val root = bar.rootView as? ViewGroup ?: return null
        windows[root]?.get()?.dispose()
        return Binding(bar, root, volume, value).also {
            bindings[bar] = WeakReference(it); windows[root] = WeakReference(it)
            try { it.start() } catch (_: Throwable) { it.dispose() }
        }
    }

    private inner class Binding(bar: SeekBar, root: ViewGroup, private val volume: Boolean,
        private val value: (Int) -> String?) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val sliderRef = WeakReference(bar)
        private val windowRef = WeakReference(root)
        private val label = TextView(bar.context).apply {
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            textSize = 40f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            val density = resources.displayMetrics.density
            setPadding((20 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (12 * density).toInt())
            background = GradientDrawable().apply { setColor(0xe6202020.toInt()); cornerRadius = 20 * density }
        }
        private val keyguard = bar.context.getSystemService(KeyguardManager::class.java)
        private val accessibility = bar.context.getSystemService(AccessibilityManager::class.java)
        private val started = SystemClock.elapsedRealtime()
        private val nativeBackground = label.background
        private var blurred = false
        private var nextBlurCheck = 0L
        private var observer: ViewTreeObserver? = null
        private var disposed = false
        val active get() = !disposed
        private val timeout = Runnable { dispose() }

        fun start() {
            val slider = sliderRef.get() ?: return
            val window = windowRef.get() ?: return
            slider.addOnAttachStateChangeListener(this)
            observer = window.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            window.overlay.add(label)
            slider.postDelayed(timeout, 30_000)
            update(slider.progress)
        }
        fun update(progress: Int) {
            if (disposed) return
            val slider = sliderRef.get()
            val window = windowRef.get()
            if (slider == null || window == null || slider.rootView !== window ||
                !PercentagePolicy.active(SystemClock.elapsedRealtime(), started, enabled(volume),
                    slider.isShown && slider.isEnabled && slider.windowVisibility == View.VISIBLE,
                    slider.isAttachedToWindow, slider.display?.displayId == 0, SafeModeRuntime.blocked,
                    keyguard?.isKeyguardLocked != false, accessibility?.isTouchExplorationEnabled != false)) {
                dispose(); return
            }
            val text = value(progress) ?: run { dispose(); return }
            if (label.text.toString() != text) label.text = text
            val insets = window.rootWindowInsets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                ?: run { dispose(); return }
            label.measure(View.MeasureSpec.makeMeasureSpec(window.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(window.height, View.MeasureSpec.AT_MOST))
            val place = PercentagePolicy.place(window.width, window.height, label.measuredWidth, label.measuredHeight,
                insets.left, insets.top, insets.right, insets.bottom, top, window.resources.displayMetrics.density,
                window.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ?: run { dispose(); return }
            label.layout(place.left, place.top, place.left + label.measuredWidth, place.top + label.measuredHeight)
            if (SystemClock.elapsedRealtime() >= nextBlurCheck) {
                nextBlurCheck = SystemClock.elapsedRealtime() + 1000
                val useBlur = settings.isOn(Config.BLUR) &&
                    runCatching { backdrop?.allowed(label.context) == true }.getOrDefault(false)
                if (useBlur && !blurred) {
                    label.background = null
                    label.clipToOutline = true
                    label.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, 20 * view.resources.displayMetrics.density)
                        }
                    }
                    blurred = backdrop!!.attach(label, true, 20 * label.resources.displayMetrics.density, 0xa0000000.toInt())
                    if (!blurred) label.background = nativeBackground
                } else if (!useBlur && blurred) {
                    backdrop!!.clear(label, true); blurred = false; label.background = nativeBackground
                }
            }
        }
        fun dispose() {
            if (disposed) return
            disposed = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            sliderRef.get()?.let {
                it.removeCallbacks(timeout); it.removeOnAttachStateChangeListener(this)
                if (bindings[it]?.get() === this) { bindings.remove(it); panelSources.remove(it) }
            }
            windowRef.get()?.let {
                if (blurred) backdrop?.clear(label, true)
                blurred = false
                it.overlay.remove(label)
                if (windows[it]?.get() === this) windows.remove(it)
            }
            label.background = null; sliderRef.clear(); windowRef.clear()
        }
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { dispose() }
        override fun onPreDraw(): Boolean {
            runCatching { sliderRef.get()?.let { update(it.progress) } }.onFailure { runCatching { dispose() } }
            return true
        }
    }

    private fun installClassic(loader: ClassLoader): Int {
        val t = PercentageLegacyTargets
        val gate = AtomicBoolean(false)
        val methods = (t.classic + t.classicCallers).distinct().associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        if (!methods.values.all { HookRuntime.deoptimize(it, "${Config.FEATURE}/classicDispatch") }) return 0
        val count = t.classic.count { spec ->
            HookRuntime.hook(methods.getValue(spec), "${Config.FEATURE}/classic/${spec.name}") { chain ->
                val bar = chain.args[0] as? SeekBar
                if (spec.name == "onStopTrackingTouch") {
                    try { chain.proceed() } finally {
                        if (bar != null && Looper.myLooper() == Looper.getMainLooper()) bindings[bar]?.get()?.dispose()
                    }
                } else {
                    val original = chain.proceed()
                    if (gate.get() && bar != null && Looper.myLooper() == Looper.getMainLooper() &&
                        enabled(false) && supported(bar, false)) runCatching {
                        if (spec.name == "onStartTrackingTouch") {
                            val weak = WeakReference(bar)
                            start(bar, false) { progress -> weak.get()?.let {
                                if (progress !in it.min..it.max) null else SliderValuePolicy.brightness(progress, it.min, it.max)
                            } }
                        } else bindings[bar]?.get()?.update(bar.progress)
                    }.onFailure { bindings[bar]?.get()?.dispose() }
                    original
                }
            }
        }
        gate.set(count == t.classic.size)
        return if (gate.get()) count else 0
    }

    private fun installVolumePanel(loader: ClassLoader): Int {
        val t = PercentageLegacyTargets
        val gate = AtomicBoolean(false)
        val methods = (t.volume + t.queries + t.volumeCallers + PercentageTargets.callers).distinct().associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val fields = t.fields.associate { (owner, name, type) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(this.type.name == type && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        if (!methods.values.all { HookRuntime.deoptimize(it, "${Config.FEATURE}/volumePanelDispatch") }) return 0
        val owned = WeakHashMap<Any, WeakReference<Binding>>()
        data class Pending(val bar: WeakReference<SeekBar>, val callback: Runnable)
        val pending = WeakHashMap<Any, Pending>()
        fun cancel(owner: Any) {
            pending.remove(owner)?.let { it.bar.get()?.removeCallbacks(it.callback) }
            owned.remove(owner)?.get()?.dispose()
        }
        fun attach(owner: Any, column: Any) {
            if (!gate.get() || !enabled(true) || SafeModeRuntime.blocked || !fields.getValue("mShowing").getBoolean(owner)) return
            val bar = fields.getValue("slider").get(column) as? SeekBar ?: return
            val stream = methods.getValue(t.stream).invoke(column) as Int
            if (fields.getValue("mActiveStream").getInt(owner) != stream || !supported(bar, true)) return
            val previous = panelSources[bar]
            if (previous == null || previous.owner.get() !== owner || previous.column.get() !== column || previous.stream != stream)
                bindings[bar]?.get()?.dispose()
            val panelRef = WeakReference(owner); val columnRef = WeakReference(column); val barRef = WeakReference(bar)
            start(bar, true) { progress ->
                val panel = panelRef.get(); val current = columnRef.get(); val slider = barRef.get()
                if (panel == null || current == null || slider == null ||
                    !fields.getValue("mShowing").getBoolean(panel) ||
                    fields.getValue("mActiveStream").getInt(panel) != stream ||
                    fields.getValue("slider").get(current) !== slider) null
                else methods.getValue(t.state).invoke(current)?.let { state ->
                    val tracking = methods.getValue(t.tracking).invoke(current) == true
                    val level = if (tracking && progress in slider.min..slider.max && slider.max > 0)
                        methods.getValue(t.level).invoke(null, slider.max, progress) as Int
                    else fields.getValue("level").getInt(state)
                    PercentagePolicy.panelVolume(level, fields.getValue("levelMin").getInt(state),
                        fields.getValue("levelMax").getInt(state), fields.getValue("muted").getBoolean(state),
                        slider.max, progress)
                }
            }?.takeIf { it.active }?.let {
                panelSources[bar] = PanelSource(panelRef, columnRef, stream)
                owned[owner] = WeakReference(it)
            }
        }
        fun postActive(owner: Any) {
            if (!enabled(true) || SafeModeRuntime.blocked || !fields.getValue("mShowing").getBoolean(owner)) return
            val column = methods.getValue(t.active).invoke(owner) ?: return
            val bar = fields.getValue("slider").get(column) as? SeekBar ?: return
            pending.remove(owner)?.let { it.bar.get()?.removeCallbacks(it.callback) }
            val panelRef = WeakReference(owner); val columnRef = WeakReference(column)
            val callback = Runnable {
                panelRef.get()?.let { panel ->
                    pending.remove(panel)
                    runCatching { columnRef.get()?.let { attach(panel, it) } }.onFailure { cancel(panel) }
                }
            }
            pending[owner] = Pending(WeakReference(bar), callback)
            // A freshly shown dialog may not have its window yet. View.post waits for attach;
            // the runnable only captures weak identities and rechecks showing/stream/feature.
            bar.post(callback)
        }
        val count = t.volume.count { spec ->
            HookRuntime.hook(methods.getValue(spec), "${Config.FEATURE}/volumePanel/${spec.name}") { chain ->
                val owner = if (spec.owner == t.LISTENER) fields.getValue("this\$0").get(chain.thisObject) else chain.thisObject
                if (spec == t.dismiss) {
                    try { chain.proceed() } finally {
                        if (owner != null && Looper.myLooper() == Looper.getMainLooper()) cancel(owner)
                    }
                } else {
                    val original = chain.proceed()
                    if (gate.get() && owner != null && Looper.myLooper() == Looper.getMainLooper()) runCatching {
                        when (spec) {
                            t.show -> postActive(owner)
                            t.update -> attach(owner, chain.args[0]!!)
                            else -> attach(owner, fields.getValue("mColumn").get(chain.thisObject))
                        }
                    }.onFailure { cancel(owner) }
                    original
                }
            }
        }
        gate.set(count == t.volume.size)
        return if (gate.get()) count else 0
    }

    private fun install(loader: ClassLoader): Int {
        val t = PercentageTargets
        val methods = (t.methods + t.callers).distinct().associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val ownerField = Class.forName(t.VOLUME, false, loader).getDeclaredField("this\$0").apply {
            require(type.name == SliderValueTargets.VOLUME); isAccessible = true
        }
        val volumeFields = listOf("streamMaxVolume" to "int", "muted" to "boolean").associate { (name, type) ->
            name to ownerField.type.getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
        }
        val volumeLevel = NotificationHooks.resolve(loader, SliderValueTargets.volumeLevel) ?: return 0
        if (!methods.values.all { HookRuntime.deoptimize(it, "${Config.FEATURE}/dispatch") }) return 0
        val installed = t.methods.count { target ->
            HookRuntime.hook(methods.getValue(target), "${Config.FEATURE}/${target.owner}/${target.name}") { chain ->
                val bar = chain.args[0] as? SeekBar
                if (target.name == "onStopTrackingTouch") {
                    try { chain.proceed() } finally {
                        if (bar != null && Looper.myLooper() == Looper.getMainLooper()) bindings[bar]?.get()?.dispose()
                    }
                } else {
                    val result = chain.proceed()
                    if (!ready.get() || bar == null || Looper.myLooper() != Looper.getMainLooper()) return@hook result
                    val volume = target.owner == t.VOLUME
                    if (!enabled(volume) || SafeModeRuntime.blocked) return@hook result
                    val host = supported ?: runCatching {
                        val pm = bar.context.packageManager
                        SliderValuePolicy.supported(pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                            pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode)
                    }.getOrDefault(false).also { supported = it }
                    if (!host || bar.display?.displayId != 0) return@hook result
                    if (target.name == "onStartTrackingTouch" && bindings[bar]?.get() == null) {
                        val root = bar.rootView as? ViewGroup ?: return@hook result
                        windows[root]?.get()?.dispose()
                        val owner = WeakReference(if (volume) ownerField.get(chain.thisObject) else null)
                        val slider = WeakReference(bar)
                        val binding = Binding(bar, root, volume) { progress ->
                            val view = slider.get()
                            if (view == null || progress !in view.min..view.max) null
                            else if (!volume) SliderValuePolicy.brightness(progress, view.min, view.max)
                            else owner.get()?.let {
                                SliderValuePolicy.volume(volumeLevel.invoke(it, progress) as Int,
                                    volumeFields.getValue("streamMaxVolume").getInt(it), volumeFields.getValue("muted").getBoolean(it))
                            }
                        }
                        bindings[bar] = WeakReference(binding); windows[root] = WeakReference(binding)
                        binding.start()
                    } else if (target.name == "onProgressChanged") bindings[bar]?.get()?.update(chain.args[1] as Int)
                    result
                }
            }
        }
        ready.set(installed == t.methods.size)
        return if (ready.get()) installed else 0
    }

    companion object {
        fun inlineEnabled(settings: HookSettings, feature: String) = settings.isOn(Config.FEATURE) &&
            Config.style(settings.string(Config.STYLE)) == 1 && settings.isOn(
                if (feature == SliderValuePolicy.VOLUME) Config.VOLUME else Config.BRIGHTNESS)
        fun install(loader: ClassLoader, hostLoader: ClassLoader, settings: HookSettings): Int =
            if (Config.style(settings.string(Config.STYLE)) == 1) SliderValuePolicy.features
                .filter { inlineEnabled(settings, it) }.sumOf { SliderValueHooks.install(loader, settings, it) }
            else PercentageHooks(settings, hostLoader).let { hooks ->
                hooks.install(loader) + hooks.installVolumePanel(loader)
            }
        fun installClassic(loader: ClassLoader, settings: HookSettings): Int =
            if (Config.style(settings.string(Config.STYLE)) != 2 || !settings.isOn(Config.BRIGHTNESS)) 0
            else PercentageHooks(settings, loader).installClassic(loader)
    }
}
