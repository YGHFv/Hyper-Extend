/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Matrix
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.IslandMediaSettings as Settings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal class IslandMediaHooks private constructor(loader: ClassLoader) {
    private val t = IslandMediaTargets
    private val methods = t.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
    private val fields = t.fields.associate { (owner, name, type) ->
        (owner to name) to StatusIconHost.field(loader, owner, name, type)
    }
    private val constructor = Class.forName(t.SET, false, loader).getDeclaredConstructor().apply { isAccessible = true }
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private var ready = false
    private fun field(owner: String, name: String) = fields.getValue(owner to name)
    private fun holder(owner: Any, name: String) = field(t.BINDER, name).get(owner)
    private fun view(holder: Any, name: String) = field(t.HOLDER, name).get(holder) as? View
    private fun enabled(feature: String) = ready && !SafeModeRuntime.blocked && HookRuntime.settings().isOn(feature)
    private fun current(context: Context): Boolean = context.packageName == "com.android.systemui"
    private fun main() = Looper.myLooper() == Looper.getMainLooper()

    private data class ImageState(val type: ImageView.ScaleType, val values: List<Float>) {
        fun matrix() = Matrix().apply { setValues(values.toFloatArray()) }
        companion object {
            fun of(view: ImageView) = ImageState(view.scaleType,
                FloatArray(9).also { view.imageMatrix.getValues(it) }.toList())
        }
    }

    private inner class Binding(owner: Any, value: Any, val root: View) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val ownerRef = WeakReference(owner)
        private val holderRef = WeakReference(value)
        private val sizes = listOf("titleText", "artistText", "elapsedTimeView", "totalTimeView").map {
            (view(value, it) as TextView) to IslandOwnedValue<Float>()
        }
        private val images = (0..4).map { (view(value, "action$it") as ImageButton) to IslandOwnedValue<ImageState>() }
        private val visibility = listOf("seamless", "appIcon", "albumView").associateWith {
            requireNotNull(view(value, it)) to IslandOwnedValue<Int>()
        }
        private var observer: ViewTreeObserver? = null
        private var disposed = false
        fun belongsTo(other: Any?) = ownerRef.get() === other
        private fun owns(): Boolean {
            val o = ownerRef.get() ?: return false
            val h = holderRef.get() ?: return false
            return (holder(o, "holder") === h || holder(o, "dummyHolder") === h) && view(h, "player") === root
        }
        private fun usable() = main() && current(root.context) && root.display?.displayId == 0 &&
            root.isAttachedToWindow && root.isShown &&
            root.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false &&
            root.context.getSystemService(PowerManager::class.java)?.isInteractive == true
        fun start() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
        }
        fun restore() {
            for ((v, state) in sizes) {
                val size = state.restore(v.textSize)
                if (size != v.textSize) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
            for ((v, state) in images) setImage(v, state.restore(ImageState.of(v)))
            for ((v, state) in visibility.values) {
                val value = state.restore(v.visibility)
                if (v.visibility != value) v.visibility = value
            }
        }
        private fun setImage(v: ImageView, desired: ImageState) {
            if (ImageState.of(v) == desired) return
            v.scaleType = desired.type
            v.imageMatrix = desired.matrix()
        }
        fun refresh() {
            if (!owns()) { dispose(); return }
            if (!usable()) { restore(); return }
            val settings = HookRuntime.settings()
            val text = enabled(Settings.TEXT)
            val layout = enabled(Settings.LAYOUT)
            val rawSizes = listOf(settings.number("${Settings.TEXT}.title", 180, 120, 360),
                settings.number("${Settings.TEXT}.artist", 120, 80, 240),
                settings.number("${Settings.TEXT}.time", 130, 50, 200))
            for ((index, pair) in sizes.withIndex()) {
                val (v, state) = pair
                val desired = if (text) IslandMediaPolicy.textSize(rawSizes[minOf(index, 2)], 1f)?.let {
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, it, v.resources.displayMetrics)
                        .takeIf { size -> size.isFinite() && size > 0f }
                } else null
                val size = if (desired == null) state.restore(v.textSize) else state.apply(v.textSize, desired)
                if (size != v.textSize) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            }
            val album = settings.number("${Settings.LAYOUT}.album", 0, 0, 2)
            for ((name, pair) in visibility) {
                val (v, state) = pair
                val hide = layout && when (name) {
                    "seamless" -> settings.isOn("${Settings.LAYOUT}.hide_seamless")
                    "appIcon" -> album != 0
                    else -> album == 2
                }
                val next = if (hide) state.apply(v.visibility, View.GONE) else state.restore(v.visibility)
                if (v.visibility != next) v.visibility = next
            }
            val data = ownerRef.get()?.let { field(t.BINDER, "mediaData").get(it) }
            val semantic = data != null && field(t.DATA, "semanticActions").get(data) != null
            val primary = settings.number("${Settings.LAYOUT}.button_size", 140, 50, 200)
            val custom = settings.number("${Settings.LAYOUT}.custom_button_size", 140, 50, 200)
            for ((index, pair) in images.withIndex()) {
                val (v, state) = pair
                val size = if (layout) MediaButtonSizePolicy.size(primary, custom,
                    IslandMediaPolicy.mainAction(semantic, index)) else null
                val d = v.drawable
                val width = v.width - v.paddingLeft - v.paddingRight
                val height = v.height - v.paddingTop - v.paddingBottom
                val scale = if (size == null || d == null || v.visibility != View.VISIBLE) null else
                    MediaButtonSizePolicy.scale(size, width, height, d.intrinsicWidth, d.intrinsicHeight)
                val old = ImageState.of(v)
                if (scale == null || d == null) setImage(v, state.restore(old)) else {
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((width - d.intrinsicWidth * scale) / 2, (height - d.intrinsicHeight * scale) / 2)
                    }
                    val desired = ImageState(ImageView.ScaleType.MATRIX,
                        FloatArray(9).also { matrix.getValues(it) }.toList())
                    setImage(v, state.apply(old, desired))
                }
            }
        }
        fun dispose() {
            if (disposed) return
            disposed = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            root.removeOnAttachStateChangeListener(this)
            restore()
        }
        fun guarded(block: () -> Unit) {
            if (disposed) return
            try { block() } catch (error: Throwable) {
                ModuleLog.error("island_media: restore owned styling after failure", error)
                runCatching { dispose() }
            }
        }
        override fun onViewAttachedToWindow(v: View) = guarded {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            refresh()
        }
        override fun onViewDetachedFromWindow(v: View) = guarded {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            restore()
        }
        override fun onPreDraw(): Boolean { guarded { refresh() }; return true }
    }

    private fun forOwner(owner: Any?, block: (Binding) -> Unit) {
        bindings.values.mapNotNull { it.get() }.filter { it.belongsTo(owner) }.forEach { it.guarded { block(it) } }
    }

    private class LayoutCall(val owner: Any, val native: Any, var replacement: Any? = null)
    private val layoutCall = ThreadLocal<LayoutCall?>()
    // 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
    @SuppressLint("NewApi")
    private fun install(): Int {
        if (!(listOf(t.attach, t.detach, t.info, t.seamless, t.actions, t.reset, t.load) + t.callers)
                .all { HookRuntime.deoptimize(methods.getValue(it), "island_media/caller") }) return 0
        val hooks = mutableListOf<Boolean>()
        hooks += HookRuntime.hook(methods.getValue(t.reset), "island_media/layout-scope") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val context = field(t.CONTROLLER, "context").get(owner) as? Context ?: return@hook chain.proceed()
            if (!enabled(Settings.LAYOUT) || !main() || !current(context) || context.display?.displayId != 0 ||
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false)
                return@hook chain.proceed()
            val template = field(t.CONTROLLER, "normalLayoutIsland")
            val frame = LayoutCall(owner, template.get(owner) ?: return@hook chain.proceed())
            val previous = layoutCall.get()
            layoutCall.set(frame)
            try { chain.proceed() } finally {
                layoutCall.set(previous)
                if (previous == null) layoutCall.remove()
                if (frame.replacement != null && template.get(owner) === frame.replacement) template.set(owner, frame.native)
            }
        }
        hooks += HookRuntime.hookAfter(methods.getValue(t.load), "island_media/layout-copy") { chain, result ->
            val frame = layoutCall.get()
            if (frame != null && chain.thisObject === frame.native && chain.args[1] == 0x7f180018 &&
                enabled(Settings.LAYOUT) && main()) {
                val context = chain.args[0] as Context
                val roots = listOf("miuiPlayerHolder", "miuiDummyPlayerHolder").mapNotNull {
                    field(t.CONTROLLER, it).get(frame.owner)?.let { h -> view(h, "player") }
                }
                if (roots.size == 2 && roots.all { root -> t.ids.values.all { root.findViewById<View>(it) != null } }) {
                    val s = HookRuntime.settings()
                    val options = MediaLayoutOptions(s.number("${Settings.LAYOUT}.album", 0, 0, 2),
                        s.number("${Settings.LAYOUT}.order", 0, 0, 2), s.isOn("${Settings.LAYOUT}.left_aligned"),
                        s.isOn("${Settings.LAYOUT}.hide_seamless"), s.number("${Settings.LAYOUT}.title_margin", 210, 0, 480))
                    // Visibility is owned by the binding, so disable can restore it without
                    // pretending a hidden value from our template was the native baseline.
                    val edits = IslandMediaPolicy.edits(options, s.number("${Settings.LAYOUT}.artist_margin", 20, 0, 360),
                        context.resources.displayMetrics.density).filter { it.method != "setVisibility" }
                    if (edits.isNotEmpty()) {
                        val copy = constructor.newInstance()
                        methods.getValue(t.clone).invoke(copy, frame.native)
                        for (edit in edits) methods.getValue(t.edits.single { it.name == edit.method })
                            .invoke(copy, *edit.arguments.toTypedArray())
                        frame.replacement = copy
                        field(t.CONTROLLER, "normalLayoutIsland").set(frame.owner, copy)
                    }
                }
            }
            result
        }
        hooks += HookRuntime.hook(methods.getValue(t.attach), "island_media/attach") { chain ->
            if (ready && main()) forOwner(chain.thisObject) { it.dispose() }
            val result = chain.proceed()
            val owner = chain.thisObject
            if (ready && main() && owner != null) for (name in listOf("holder", "dummyHolder")) {
                val h = holder(owner, name) ?: continue
                val root = view(h, "player") ?: continue
                bindings.remove(root)?.get()?.dispose()
                val binding = Binding(owner, h, root)
                bindings[root] = WeakReference(binding)
                binding.guarded { binding.start() }
            }
            result
        }
        hooks += HookRuntime.hook(methods.getValue(t.detach), "island_media/detach") { chain ->
            if (ready && main()) forOwner(chain.thisObject) { it.dispose() }
            chain.proceed()
        }
        for (spec in listOf(t.info, t.seamless, t.actions)) {
            hooks += HookRuntime.hook(methods.getValue(spec), "island_media/native-${spec.name}") { chain ->
                if (ready && main()) forOwner(chain.thisObject) { it.restore() }
                try { chain.proceed() } finally {
                    if (ready && main()) forOwner(chain.thisObject) { it.refresh() }
                }
            }
        }
        ready = hooks.all { it }
        return if (ready) hooks.size else 0
    }
    companion object { fun install(loader: ClassLoader): Int = IslandMediaHooks(loader).install() }
}
