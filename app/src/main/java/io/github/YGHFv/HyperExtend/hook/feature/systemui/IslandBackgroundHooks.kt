/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.IslandMediaSettings as Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.ref.WeakReference
import java.util.WeakHashMap

// 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
@SuppressLint("NewApi")
internal class IslandBackgroundHooks private constructor(loader: ClassLoader) {
    private val t = IslandBackgroundTargets
    private val methods = t.methods.distinct().associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
    private val fields = t.fields.associate { (owner, name, type) ->
        (owner to name) to StatusIconHost.field(loader, owner, name, type)
    }
    private val resolver = MediaPaletteResolver(loader)
    private val bindings = WeakHashMap<Any, WeakReference<Binding>>()
    private val bars = WeakHashMap<View, WeakReference<Surface>>()
    private var ready = false
    private fun field(owner: String, name: String) = fields.getValue(owner to name)
    private fun holder(owner: Any, name: String) = field(t.BINDER, name).get(owner)
    private fun view(holder: Any, name: String) = field(t.HOLDER, name).get(holder) as View
    private fun main() = Looper.myLooper() == Looper.getMainLooper()
    private fun options(): IslandBackgroundOptions {
        val s = HookRuntime.settings()
        return IslandBackgroundOptions(s.number("${Settings.BACKGROUND}.mode", 0, 0, 4),
            s.number("${Settings.BACKGROUND}.ambient", 0, 0, 2),
            s.number("${Settings.BACKGROUND}.blur", 10, 0, 20),
            s.isOn("${Settings.BACKGROUND}.inverse"), s.isOn("${Settings.BACKGROUND}.transition"),
            s.isOn("${Settings.BACKGROUND}.optimized"))
    }

    private data class Tint(val value: ColorStateList?)
    private inner class Surface(value: Any) {
        val holderRef = WeakReference(value)
        val rootRef = WeakReference(view(value, "player"))
        val bgRef = WeakReference(view(value, "mediaBgView") as ViewGroup)
        private val shader = field(t.BG, "mShader").get(bgRef.get())
        private val childRef = WeakReference(field(t.SHADER, "view").get(shader) as View)
        private val alpha = IslandOwnedValue<Float>()
        private var nativeBackground: Drawable? = null
        private var ownedBackground: Drawable? = null
        private var lastRadius = Float.NaN
        private var revision = -1L
        private val barRef = WeakReference(view(value, "seekBar"))
        private val progressColors = listOf("ForegroundPrimaryColor", "ForegroundPrimaryDisableColor",
            "BackgroundPrimaryColor", "BackgroundPrimaryDisableColor").associateWith { IslandOwnedValue<Int>() }
        var shaderTint: BlendModeColorFilter? = null
            private set
        private var tintColor: Int? = null
        private val text = listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView").map {
            WeakReference(view(value, it) as TextView) to IslandOwnedValue<Tint>()
        }
        private val images = ((0..4).map { "action$it" } + "seamlessIcon").map {
            WeakReference(view(value, it) as ImageView) to IslandOwnedValue<Tint>()
        }
        fun valid(owner: Any): Boolean {
            val h = holderRef.get() ?: return false
            val bg = bgRef.get() ?: return false
            val child = childRef.get() ?: return false
            return (holder(owner, "holder") === h || holder(owner, "dummyHolder") === h) &&
                view(h, "player") === rootRef.get() && view(h, "mediaBgView") === bg &&
                field(t.BG, "mShader").get(bg) === shader && bg.childCount == 1 &&
                bg.getChildAt(0) === child && child.parent === bg
        }
        fun usable(): Boolean {
            val root = rootRef.get() ?: return false
            return IslandBackgroundPolicy.eligible(ready && !SafeModeRuntime.blocked &&
                HookRuntime.settings().isOn(Settings.BACKGROUND),
                root.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                root.display?.displayId, root.isAttachedToWindow,
                root.isShown && root.windowVisibility == View.VISIBLE,
                root.context.getSystemService(PowerManager::class.java)?.isInteractive == true,
                root.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false)
        }
        fun restoreColors() {
            barRef.get()?.let { bar ->
                if (bars[bar]?.get() === this) bars.remove(bar)
                for ((name, state) in progressColors) {
                    val original = field(MediaProgressTargets.BAR, "m$name").getInt(bar)
                    val next = state.restore(original)
                    if (next != original) methods.getValue(t.methods.single {
                        it.owner == MediaProgressTargets.BAR && it.name == "set$name"
                    }).invoke(bar, next)
                }
            }
            shaderTint = null; tintColor = null
            for ((ref, state) in text) ref.get()?.let { v ->
                val next = state.restore(Tint(v.textColors)).value
                if (next != null && next !== v.textColors) v.setTextColor(next)
            }
            for ((ref, state) in images) ref.get()?.let { v ->
                val next = state.restore(Tint(v.imageTintList)).value
                if (next !== v.imageTintList) v.imageTintList = next
            }
        }
        fun restore() {
            restoreColors()
            childRef.get()?.let { it.alpha = alpha.restore(it.alpha) }
            bgRef.get()?.let { if (ownedBackground != null && it.background === ownedBackground) it.background = nativeBackground }
            (ownedBackground as? MediaArtworkDrawable)?.finishTransition()
            ownedBackground?.callback = null
            ownedBackground = null; nativeBackground = null; revision = -1L; lastRadius = Float.NaN
        }
        fun apply(result: MediaArtworkResult?, nextRevision: Long, o: IslandBackgroundOptions, phase: Float) {
            val bg = bgRef.get() ?: return
            val child = childRef.get() ?: return
            if (!usable() || !o.custom || o.needsArtwork && result == null) { restore(); return }
            // Never steal a background supplied by a newer native writer.
            if (bg.background != null && bg.background !== ownedBackground) { restore(); return }
            val radius = (methods.getValue(t.radius).invoke(shader) as Float)
                .takeIf { it.isFinite() && it >= 0f } ?: run { restore(); return }
            if (radius != lastRadius) { restore(); lastRadius = radius }
            if (o.mode != 0) {
                val colors = result?.palette ?: run { restore(); return }
                val existing = ownedBackground as? MediaArtworkDrawable
                if (existing == null) {
                    nativeBackground = bg.background
                    ownedBackground = MediaArtworkDrawable(result.pixels, colors, o.mode, radius)
                    bg.background = ownedBackground
                } else if (revision != nextRevision) {
                    existing.update(result.pixels, colors, o.transition && ValueAnimator.areAnimatorsEnabled())
                }
                val palette = (ownedBackground as MediaArtworkDrawable).palette()
                barRef.get()?.let { bar ->
                    val values = listOf(palette.primary, MediaPalettePolicy.withAlpha(palette.primary, 97),
                        MediaPalettePolicy.withAlpha(palette.primary, 46), MediaPalettePolicy.withAlpha(palette.primary, 25))
                    progressColors.entries.forEachIndexed { index, (name, state) ->
                        val original = field(MediaProgressTargets.BAR, "m$name").getInt(bar)
                        val next = state.apply(original, values[index])
                        if (next != original) methods.getValue(t.methods.single {
                            it.owner == MediaProgressTargets.BAR && it.name == "set$name"
                        }).invoke(bar, next)
                    }
                    if (tintColor != palette.primary) {
                        tintColor = palette.primary
                        shaderTint = BlendModeColorFilter(palette.primary, BlendMode.MODULATE)
                    }
                    bars[bar] = WeakReference(this)
                }
                for ((index, pair) in text.withIndex()) pair.first.get()?.let { v ->
                    val value = ColorStateList.valueOf(if (index == 1) palette.secondary else palette.primary)
                    val next = pair.second.apply(Tint(v.textColors), Tint(value)).value
                    if (next !== v.textColors) v.setTextColor(next)
                }
                for ((ref, state) in images) ref.get()?.let { v ->
                    val next = state.apply(Tint(v.imageTintList), Tint(ColorStateList.valueOf(palette.primary))).value
                    if (next !== v.imageTintList) v.imageTintList = next
                }
            } else if (o.ambient == 2) {
                if (ownedBackground !is MediaAmbientDrawable) {
                    nativeBackground = bg.background
                    ownedBackground = MediaAmbientDrawable(radius)
                    bg.background = ownedBackground
                }
                (ownedBackground as MediaAmbientDrawable).frame(requireNotNull(result).ambientDark, false, phase)
                restoreColors()
            } else {
                restoreColors()
            }
            revision = nextRevision
            // Retain the actual shader view and all native start/pause/sync state.
            // This deliberately makes no claim that native shader CPU work stops.
            child.alpha = alpha.apply(child.alpha, 0f)
        }
    }

    private inner class Binding(owner: Any, holders: List<Any>) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val ownerRef = WeakReference(owner)
        private val surfaces = holders.map(::Surface)
        private val observers = mutableListOf<ViewTreeObserver>()
        private val job = MediaArtworkJob()
        private val clock = IslandAmbientClock()
        private var source = WeakReference<Drawable>(null)
        private var data = WeakReference<Any>(null)
        private var config: IslandBackgroundOptions? = null
        private var result: MediaArtworkResult? = null
        private var revision = 0L
        private var disposed = false
        private var failed = false
        fun start() {
            surfaces.forEach { it.rootRef.get()?.addOnAttachStateChangeListener(this) }
            observe()
            refresh()
        }
        private fun observe() {
            observers.filter { it.isAlive }.forEach { it.removeOnPreDrawListener(this) }
            observers.clear()
            surfaces.mapNotNull { it.rootRef.get() }.filter { it.isAttachedToWindow }
                .map { it.viewTreeObserver }.distinct().forEach { it.addOnPreDrawListener(this); observers += it }
        }
        fun beforeNative() { surfaces.forEach { it.restoreColors() } }
        private fun clear() {
            job.cancel(); result = null; config = null; source.clear(); data.clear()
            clock.reset(); surfaces.forEach { it.restore() }
        }
        fun refresh() {
            if (disposed || failed) return
            val owner = ownerRef.get() ?: run { dispose(); return }
            if (!surfaces.all { it.valid(owner) }) { dispose(); return }
            val o = options()
            if (!o.custom || surfaces.none { it.usable() }) { clear(); return }
            val nextData = field(t.BINDER, "mediaData").get(owner)
            val nextSource = (field(t.BINDER, "artWorkDrawable").get(owner)
                ?: field(t.BINDER, "appIconDrawable").get(owner)) as? Drawable
            if (config != o || data.get() !== nextData || source.get() !== nextSource) {
                val compatible = config == o && nextData != null
                job.cancel()
                if (!compatible) { result = null; surfaces.forEach { it.restore() } }
                config = o; data = WeakReference(nextData); source = WeakReference(nextSource)
                if (o.needsArtwork) {
                    val pixels = nextSource?.let(MediaArtworkJob::snapshot)
                    if (pixels == null) { result = null; surfaces.forEach { it.restore() }; return }
                    val weak = WeakReference(this)
                    val colorResolver = resolver
                    // Resolver captures no holder/context; callback is weak and generation checked.
                    job.submit(pixels, o.mode, o.blur,
                        { colorResolver.resolve(it, o.mode, o.inverse, o.optimized) }) { value ->
                        weak.get()?.guarded {
                            result = value; revision++
                            refresh()
                        }
                    }
                }
            }
            val playing = nextData != null && field(t.DATA, "isPlaying").get(nextData) == true
            val moving = playing && o.mode == 0 && o.ambient == 2 && ValueAnimator.areAnimatorsEnabled()
            val phase = clock.frame(SystemClock.uptimeMillis(), moving)
            surfaces.forEach { it.apply(result, revision, o, phase) }
            if (moving && result != null) surfaces.forEach { surface ->
                if (surface.usable()) surface.bgRef.get()?.postInvalidateDelayed(33L)
            }
        }
        fun dispose() {
            if (disposed) return
            disposed = true
            observers.filter { it.isAlive }.forEach { it.removeOnPreDrawListener(this) }; observers.clear()
            surfaces.forEach { it.rootRef.get()?.removeOnAttachStateChangeListener(this) }
            clear()
        }
        fun guarded(block: Binding.() -> Unit) {
            if (disposed || failed) return
            try { block() } catch (error: Throwable) {
                failed = true
                ModuleLog.error("island_background: restore native after failure", error)
                runCatching { dispose() }
            }
        }
        override fun onViewAttachedToWindow(v: View) = guarded { observe(); refresh() }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            observe()
            surfaces.filter { it.rootRef.get() === v }.forEach { it.restore() }
            refresh()
        }
        override fun onPreDraw(): Boolean { guarded { refresh() }; return true }
    }

    private fun install(): Int {
        if (!t.callers.distinct().all { HookRuntime.deoptimize(methods.getValue(it), "island_background/caller") }) return 0
        val hooks = mutableListOf<Boolean>()
        hooks += HookRuntime.hook(methods.getValue(MediaPaletteTargets.shaderDraw), "island_background/progress-shader") { chain ->
            val bar = chain.thisObject as? View ?: return@hook chain.proceed()
            if (!ready || !main()) return@hook chain.proceed()
            val surface = bars[bar]?.get() ?: return@hook chain.proceed()
            val tint = surface.shaderTint ?: return@hook chain.proceed()
            if (!surface.usable()) return@hook chain.proceed()
            val paint = field(MediaProgressTargets.BAR, "mPaint").get(bar) as Paint
            val original = paint.colorFilter
            if (original != null) return@hook chain.proceed()
            try { paint.colorFilter = tint; chain.proceed() } finally {
                if (paint.colorFilter === tint) paint.colorFilter = original
            }
        }
        hooks += HookRuntime.hook(IslandMediaTargets.attach.let(methods::getValue), "island_background/attach") { chain ->
            if (ready && main()) bindings.remove(chain.thisObject)?.get()?.guarded { dispose() }
            val result = chain.proceed()
            val owner = chain.thisObject
            if (ready && main() && owner != null) {
                val holders = listOf("holder", "dummyHolder").mapNotNull { holder(owner, it) }
                if (holders.size == 2) {
                    val binding = Binding(owner, holders)
                    bindings[owner] = WeakReference(binding)
                    binding.guarded { start() }
                }
            }
            result
        }
        hooks += HookRuntime.hook(methods.getValue(IslandMediaTargets.detach), "island_background/detach") { chain ->
            if (ready && main()) bindings.remove(chain.thisObject)?.get()?.guarded { dispose() }
            chain.proceed()
        }
        for (spec in listOf(t.bind, t.foreground, t.music)) {
            hooks += HookRuntime.hook(methods.getValue(spec), "island_background/${spec.name}") { chain ->
                val binding = if (ready && main()) bindings[chain.thisObject]?.get() else null
                binding?.guarded { beforeNative() }
                try { chain.proceed() } finally { binding?.guarded { refresh() } }
            }
        }
        ready = hooks.all { it }
        return if (ready) hooks.size else 0
    }
    companion object { fun install(loader: ClassLoader): Int = IslandBackgroundHooks(loader).install() }
}
