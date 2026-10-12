/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.graphics.Canvas
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.IslandMediaSettings
import io.github.YGHFv.HyperExtend.core.MediaProgressSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object IslandProgressHooks {
    private class Binding(owner: Any, holder: Any) {
        val owner = WeakReference(owner)
        val holder = WeakReference(holder)
        val drawing = MediaProgressDrawing()
    }
    fun install(loader: ClassLoader): Int {
        val feature = IslandMediaSettings.PROGRESS
        val t = IslandProgressTargets
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, type) ->
            (owner to name) to StatusIconHost.field(loader, owner, name, type)
        }
        fun field(owner: String, name: String) = fields.getValue(owner to name)
        val binder = IslandMediaTargets.BINDER
        val holder = IslandMediaTargets.HOLDER
        val barName = MediaProgressTargets.BAR
        val barClass = Class.forName(barName, false, loader)
        val progress = Class.forName(MediaProgressTargets.PROGRESS, false, loader)
        if (!methods.values.all { HookRuntime.deoptimize(it, "$feature/caller") }) return 0
        val ready = AtomicBoolean(false)
        val heightScope = MediaNativeHeightScope()
        val nativeHeight = MediaNativeHeightHooks.install(loader, feature, heightScope)
        val bars = WeakHashMap<SeekBar, Binding>()
        val animation = WeakHashMap<Any, Boolean>()
        fun main() = ready.get() && Looper.myLooper() == Looper.getMainLooper()
        fun release(owner: Any?) {
            bars.entries.removeAll { it.value.owner.get() == null || it.value.owner.get() === owner }
        }
        val attach = HookRuntime.hookAfter(methods.getValue(IslandMediaTargets.attach), "$feature/attach") { chain, result ->
            if (main()) {
                val owner = chain.thisObject
                release(owner)
                if (owner != null) for (name in listOf("holder", "dummyHolder")) {
                    val value = field(binder, name).get(owner) ?: continue
                    val bar = field(holder, "seekBar").get(value) as? SeekBar ?: continue
                    if (bar.javaClass == barClass) bars[bar] = Binding(owner, value)
                }
            }
            result
        }
        val detach = HookRuntime.hookAfter(methods.getValue(IslandMediaTargets.detach), "$feature/detach") { chain, result ->
            if (main()) release(chain.thisObject)
            result
        }
        val changed = HookRuntime.hookAfter(methods.getValue(MediaProgressTargets.changed), "$feature/progress") { chain, result ->
            val value = chain.args[0]
            if (main() && progress.isInstance(value)) {
                val owner = field(MediaProgressTargets.OBSERVER, "this\$0").get(chain.thisObject)
                animation[owner] = MediaProgressGeometry.animate(
                    field(MediaProgressTargets.PROGRESS, "enabled").getBoolean(value),
                    field(MediaProgressTargets.PROGRESS, "listening").getBoolean(value),
                    field(MediaProgressTargets.PROGRESS, "playing").getBoolean(value),
                    field(MediaProgressTargets.PROGRESS, "scrubbing").getBoolean(value))
                bars.forEach { (bar, binding) ->
                    if (binding.owner.get()?.let { field(binder, "seekBarProgressOwner").get(it) === owner } == true)
                        bar.invalidate()
                }
            }
            result
        }
        val draw = HookRuntime.hook(methods.getValue(MediaProgressTargets.draw), "$feature/draw") { chain ->
            val bar = chain.thisObject as? SeekBar ?: return@hook chain.proceed()
            val settings = HookRuntime.settings()
            if (!main() || !settings.isOn(feature) || !bar.isAttachedToWindow || !bar.isShown ||
                bar.windowVisibility != View.VISIBLE || bar.display?.displayId != 0) return@hook chain.proceed()
            val binding = bars[bar] ?: return@hook chain.proceed()
            val owner = binding.owner.get() ?: return@hook chain.proceed()
            val value = binding.holder.get() ?: return@hook chain.proceed()
            if ((field(binder, "holder").get(owner) !== value && field(binder, "dummyHolder").get(owner) !== value) ||
                field(holder, "seekBar").get(value) !== bar ||
                bar.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode != 202602260L ||
                bar.context.getSystemService(PowerManager::class.java)?.isInteractive != true ||
                bar.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
                field(barName, "mIsDragging").getBoolean(bar) || bar.isPressed) return@hook chain.proceed()
            val mode = MediaProgressSettings.mode(settings.string("$feature.mode"))
            val density = bar.resources.displayMetrics.density
            val rawHeight = settings.number("$feature.height", 60, 10, 160)
            if (mode == 0) {
                if (field(barName, "mDeviceLevel").getInt(bar) !in 0..2) return@hook chain.proceed()
                val height = MediaProgressPolicy.height(rawHeight, density,
                    minOf(field(barName, "mProgressSeekBarMaxHeight").getInt(bar),
                        bar.height - bar.paddingTop - bar.paddingBottom)) ?: return@hook chain.proceed()
                val member = field(barName, "mProgressHeight")
                val previous = member.getInt(bar)
                try { member.setInt(bar, height); heightScope.withHeight(bar, height) { chain.proceed() } } finally {
                    if (member.getInt(bar) == height) member.setInt(bar, previous)
                }
            } else {
                val fraction = MediaProgressGeometry.fraction(bar.progress, bar.min, bar.max) ?: return@hook chain.proceed()
                val width = bar.width - bar.paddingLeft - bar.paddingRight
                val height = bar.height - bar.paddingTop - bar.paddingBottom
                if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0f) return@hook chain.proceed()
                val foreground: Int
                val background: Int
                if (field(barName, "mDeviceLevel").getInt(bar) == 2) {
                    val time = field(holder, "elapsedTimeView").get(value) as? TextView ?: return@hook chain.proceed()
                    foreground = MediaProgressGeometry.alpha(time.currentTextColor, if (bar.isEnabled) 1f else .5f)
                    background = MediaProgressGeometry.alpha(foreground, .24f)
                } else {
                    foreground = field(barName, if (bar.isEnabled) "mForegroundPrimaryColor" else "mForegroundPrimaryDisableColor").getInt(bar)
                    background = field(barName, if (bar.isEnabled) "mBackgroundPrimaryColor" else "mBackgroundPrimaryDisableColor").getInt(bar)
                }
                val animate = bar.isEnabled && animation[field(binder, "seekBarProgressOwner").get(owner)] == true &&
                    runCatching { Settings.Global.getFloat(bar.context.contentResolver,
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }.getOrDefault(false)
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
        ready.set(nativeHeight && attach && detach && changed && draw)
        return if (ready.get()) 5 else 0
    }
}
