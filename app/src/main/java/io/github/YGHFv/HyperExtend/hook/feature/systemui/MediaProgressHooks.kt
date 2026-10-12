/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.MediaProgressSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object MediaProgressHooks {
    private class Binding(controller: Any, holder: Any) {
        val controller = WeakReference(controller)
        val holder = WeakReference(holder)
        val drawing = MediaProgressDrawing()
    }
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val target = MediaProgressTargets
        val feature = MediaProgressSettings.FEATURE
        val methods = target.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = target.fields.associate { (owner, name, expected) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected); isAccessible = true
            }
        }
        val barType = Class.forName(target.BAR, false, loader)
        val progressType = Class.forName(target.PROGRESS, false, loader)
        if (!methods.values.filterNot { Modifier.isAbstract(it.modifiers) }
                .map { HookRuntime.deoptimize(it, "$feature/caller") }.all { it }) return 0
        val ready = AtomicBoolean(false)
        val heightScope = MediaNativeHeightScope()
        val nativeHeight = MediaNativeHeightHooks.install(loader, feature, heightScope)
        val bars = WeakHashMap<SeekBar, Binding>()
        val animation = WeakHashMap<Any, Boolean>()
        var version: Boolean? = null
        fun main() = ready.get() && Looper.myLooper() == Looper.getMainLooper()
        val attach = HookRuntime.hookAfter(methods.getValue(SystemUiTargets.mediaAttach), "$feature/attach") { chain, result ->
            if (main()) {
                val controller = chain.thisObject
                val holder = controller?.let { fields.getValue("holder").get(it) }
                val bar = holder?.let { fields.getValue("seekBar").get(it) as? SeekBar }
                bars.entries.removeAll { it.value.controller.get() == null || it.value.controller.get() === controller }
                if (controller != null && holder != null && bar != null && bar.javaClass == barType)
                    bars[bar] = Binding(controller, holder)
            }
            result
        }
        val detach = HookRuntime.hookAfter(methods.getValue(MediaDarkTargets.detach), "$feature/detach") { chain, result ->
            if (main()) bars.entries.removeAll {
                it.value.controller.get() == null || it.value.controller.get() === chain.thisObject
            }
            result
        }
        val observer = HookRuntime.hookAfter(methods.getValue(target.changed), "$feature/progress") { chain, result ->
            val progress = chain.args[0]
            if (main() && progressType.isInstance(progress)) {
                val owner = fields.getValue("this\$0").get(chain.thisObject)
                animation[owner] = MediaProgressGeometry.animate(fields.getValue("enabled").getBoolean(progress),
                    fields.getValue("listening").getBoolean(progress), fields.getValue("playing").getBoolean(progress),
                    fields.getValue("scrubbing").getBoolean(progress))
                bars.forEach { (bar, binding) ->
                    if (binding.controller.get()?.let { fields.getValue("seekBarProgressOwner").get(it) === owner } == true)
                        bar.invalidate()
                }
            }
            result
        }
        val draw = HookRuntime.hook(methods.getValue(target.draw), "$feature/draw") { chain ->
            val bar = chain.thisObject as? SeekBar ?: return@hook chain.proceed()
            if (!main() || !settings.isOn(feature) || bar.display?.displayId != 0 || !bar.isShown ||
                !bar.isAttachedToWindow || bar.windowVisibility != View.VISIBLE ||
                bar.context.getSystemService(PowerManager::class.java)?.isInteractive != true)
                return@hook chain.proceed()
            val binding = bars[bar] ?: return@hook chain.proceed()
            val controller = binding.controller.get() ?: return@hook chain.proceed()
            val holder = binding.holder.get() ?: return@hook chain.proceed()
            if (fields.getValue("holder").get(controller) !== holder || fields.getValue("seekBar").get(holder) !== bar)
                return@hook chain.proceed()
            val supported = version ?: runCatching {
                (bar.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L)
                    .also { version = it }
            }.getOrDefault(false)
            val ordinary = supported && runCatching {
                val material = fields.getValue("notificationMaterialStateInteractor").get(controller)
                val flow = methods.getValue(target.flowValue)
                val pendingAod = fields.getValue("mWillDoAodAnim").getBoolean(
                    methods.getValue(target.lazyGet).invoke(fields.getValue("fullAodController").get(controller)))
                methods.getValue(SystemUiTargets.flipTinyScreen).invoke(null, bar.context) == false &&
                    (bar.context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == false &&
                    flow.invoke(fields.getValue("isOnKeyguard").get(material)) == false &&
                    flow.invoke(fields.getValue("isShowingKeyguardNotification").get(material)) == false &&
                    flow.invoke(fields.getValue("enterFullAod").get(material)) == false && !pendingAod
            }.getOrDefault(false)
            if (!ordinary) return@hook chain.proceed()
            val mode = MediaProgressSettings.mode(settings.string("$feature.mode"))
            val density = bar.resources.displayMetrics.density
            val rawHeight = settings.number("$feature.height", 80, 10, 160)
            val dragging = fields.getValue("mIsDragging").getBoolean(bar) || bar.isPressed
            if (dragging) return@hook chain.proceed()
            if (mode == 0) {
                if (fields.getValue("mDeviceLevel").getInt(bar) !in 0..2) return@hook chain.proceed()
                val height = MediaProgressPolicy.height(rawHeight, density,
                    minOf(fields.getValue("mProgressSeekBarMaxHeight").getInt(bar),
                        bar.height - bar.paddingTop - bar.paddingBottom)) ?: return@hook chain.proceed()
                val current = fields.getValue("mProgressHeight")
                val old = current.getInt(bar)
                try {
                    current.setInt(bar, height)
                    heightScope.withHeight(bar, height) { chain.proceed() }
                } finally {
                    if (current.getInt(bar) == height) current.setInt(bar, old)
                }
            } else {
                val fraction = MediaProgressGeometry.fraction(bar.progress,
                    if (Build.VERSION.SDK_INT >= 26) bar.min else 0, bar.max) ?: return@hook chain.proceed()
                val width = bar.width - bar.paddingLeft - bar.paddingRight
                val height = bar.height - bar.paddingTop - bar.paddingBottom
                if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0f)
                    return@hook chain.proceed()
                val owner = fields.getValue("seekBarProgressOwner").get(controller)
                val animate = animation[owner] == true && !dragging && bar.isEnabled && runCatching {
                    Settings.Global.getFloat(bar.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
                }.getOrDefault(false)
                val foreground: Int
                val background: Int
                if (fields.getValue("mDeviceLevel").getInt(bar) == 2) {
                    // Primary-color setters are no-ops on shader-only devices. Use the actual
                    // themed time label instead of their stale constructor defaults.
                    val text = fields.getValue("elapsedTimeView").get(holder) as? TextView
                        ?: return@hook chain.proceed()
                    foreground = MediaProgressGeometry.alpha(text.currentTextColor, if (bar.isEnabled) 1f else .5f)
                    background = MediaProgressGeometry.alpha(foreground, .24f)
                } else {
                    foreground = fields.getValue(if (bar.isEnabled) "mForegroundPrimaryColor" else "mForegroundPrimaryDisableColor").getInt(bar)
                    background = fields.getValue(if (bar.isEnabled) "mBackgroundPrimaryColor" else "mBackgroundPrimaryDisableColor").getInt(bar)
                }
                binding.drawing.draw(chain.args[0] as Canvas, bar.paddingLeft.toFloat(), bar.paddingTop.toFloat(),
                    width.toFloat(), height.toFloat(), density, fraction, bar.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                    mode, MediaProgressSettings.mode(settings.string("$feature.thumb")), settings.isOn("$feature.round"),
                    settings.isOn("$feature.comet"), rawHeight / 10f * density,
                    settings.number("$feature.corner", 36, 12, 90) / 10f * density, foreground, background,
                    if (animate) MediaProgressGeometry.phase(SystemClock.uptimeMillis()) else 0f)
                if (mode == 1 && animate) bar.postInvalidateDelayed(33L)
                null
            }
        }
        ready.set(nativeHeight && attach && detach && observer && draw)
        return if (ready.get()) 5 else 0
    }
}
