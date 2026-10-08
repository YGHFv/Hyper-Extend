/*
 * Copyright (C) 2026 zhhhyyyyyy (HyperVolumeANC)
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: Apache-2.0
 * Adapted entry placement and native RingerButtonHelper reuse. Per-view lifecycle,
 * guarded discovery and acknowledged native-panel launch replace the upstream globals.
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Exact plugin 183022200 targets are recorded in systemui-plugin-host-evidence.json. */
internal object AppVolumeEntryHooks {
    private const val PLUGIN = "miui.systemui.plugin"
    private const val PREFIX = "com.android.systemui.miui.volume."
    private const val TAG = "hyperextend:app-volume-entry"
    private val conflicts = listOf("tag_app_volume_entry_root", "hypervolumeanc:app-volume-entry")
    private val states = WeakHashMap<ViewGroup, WeakReference<Entry>>()

    fun installPlugin(loader: ClassLoader): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val dialog = Class.forName(PREFIX + "MiuiVolumeDialogView", false, loader)
        val controller = Class.forName(PREFIX + "VolumePanelViewController", false, loader)
        val showPanel = controller.getDeclaredMethod("showVolumePanelH", Int::class.javaPrimitiveType)
        val dismiss = controller.getDeclaredMethod("dismissH", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val show = dialog.getDeclaredMethod("showH", Runnable::class.java)
        val hide = dialog.getDeclaredMethod("dismissH", Boolean::class.javaPrimitiveType, Runnable::class.java)
        val finish = dialog.getDeclaredMethod("lambda\$dismissH\$0", Runnable::class.java)
        val apply = Class.forName("com.android.systemui.miui.ViewStateGroup", false, loader)
            .getDeclaredMethod("apply", ViewGroup::class.java)
        val layout = dialog.getDeclaredMethod("updateDialogViewLP")
        val flip = Class.forName("miui.systemui.util.FlipUtils", false, loader).getDeclaredMethod("isFlipTiny")
        require(LinearLayout::class.java.isAssignableFrom(dialog))
        val suffix = Integer.toHexString(System.identityHashCode(loader))
        val motion = Class.forName(PREFIX + "MiuiVolumeDialogMotion", false, loader)
        for (name in listOf("updateStates", "updateStateToExpand")) {
            HookRuntime.deoptimize(motion.getDeclaredMethod(name, Boolean::class.javaPrimitiveType), "app_volume/$name")
        }
        HookRuntime.deoptimize(controller.getDeclaredMethod("dismissVolumePanel", Int::class.javaPrimitiveType), "app_volume/dismissVolumePanel")
        HookRuntime.deoptimize(controller.getDeclaredMethod("showH", Int::class.javaPrimitiveType), "app_volume/controllerShow")
        HookRuntime.deoptimize(Class.forName(PREFIX + "s", false, loader).getDeclaredMethod("run"), "app_volume/dismissCallback")
        HookRuntime.deoptimize(dialog.getDeclaredMethod("a", dialog, Runnable::class.java), "app_volume/dismissBridge")
        var count = 0
        if (HookRuntime.hookAfter(show, "app_volume/show/$suffix") { chain, original ->
                states[chain.thisObject]?.get()?.show()
                original
            }) count++
        if (HookRuntime.hook(hide, "app_volume/hide/$suffix") { chain ->
                Reflect.attempt { states[chain.thisObject]?.get()?.beginDismiss() }
                chain.proceed()
            }) count++
        if (HookRuntime.hookAfter(finish, "app_volume/finish/$suffix") { chain, original ->
                states[chain.thisObject]?.get()?.finishDismiss()
                original
            }) count++
        // Both synchronous expand and asynchronous pre-draw show pass through apply().
        if (HookRuntime.hook(apply, "app_volume/nativeState/$suffix") { chain ->
                val entry = states[chain.args[0]]?.get()
                Reflect.attempt { entry?.restoreMargin() }
                val result = chain.proceed()
                Reflect.attempt { entry?.update() }
                result
            }) count++
        if (HookRuntime.hook(layout, "app_volume/layout/$suffix") { chain ->
                val entry = states[chain.thisObject]?.get()
                Reflect.attempt { entry?.restoreMargin() }
                val result = chain.proceed()
                Reflect.attempt { entry?.update() }
                result
            }) count++
        // Do not create any rows if a lifecycle/geometry interceptor failed to install.
        if (count != 5) return count
        val ringer = Class.forName(PREFIX + "MiuiRingerModeLayout", false, loader)
        for (name in listOf("onMaterialModeChanged", "updateResources")) {
            if (HookRuntime.hookAfter(ringer.getDeclaredMethod(name), "app_volume/$name/$suffix") { chain, original ->
                    val parent = (chain.thisObject as? View)?.parent
                    states[parent]?.get()?.let { it.style(); it.update() }
                    original
            }) count++
        }
        if (HookRuntime.hook(showPanel, "app_volume/controller/$suffix") { chain ->
                Reflect.attempt {
                    val host = Reflect.readField(chain.thisObject, "mVolumeView") as? ViewGroup
                    host?.let { entry(it, dismiss, flip)?.controller = WeakReference(chain.thisObject!!) }
                }
                chain.proceed()
            }) count++
        return count
    }

    private fun entry(dialog: ViewGroup, dismiss: Method, flip: Method): Entry? {
        states[dialog]?.get()?.let { return it }
        if (dialog.findViewWithTag<View>(TAG) != null) return null
        if (conflicts.any { dialog.findViewWithTag<View>(it) != null }) return null
        val host = Reflect.readField(dialog, "mRingerModeLayout") as? ViewGroup ?: return null
        if (Reflect.readField(host, "mNeedShowDialog") != true || flip.invoke(null) == true) return null
        return Reflect.attempt {
            val id = resource(dialog.context, "layout", "miui_ringer_mode_layout")
            if (id == 0) return@attempt null
            val row = LayoutInflater.from(dialog.context).inflate(id, dialog, false) as ViewGroup
            val click = find(row, "bg_blur") ?: return@attempt null
            val icon = find(row, "icon") as? ImageView ?: return@attempt null
            val standard = find(row, "miui_standard_btn") ?: return@attempt null
            find(row, "timer_layout")?.visibility = View.GONE
            val type = host.javaClass.declaredClasses.single { it.simpleName == "RingerButtonHelper" }
            val ctor = type.getDeclaredConstructor(host.javaClass, View::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val helper = ctor.newInstance(host, row, false, false)
            // The template repeats native IDs; never let host findViewById select our copy.
            fun reidentify(view: View) {
                view.id = View.generateViewId()
                if (view is ViewGroup) for (i in 0 until view.childCount) reidentify(view.getChildAt(i))
            }
            reidentify(row)
            row.tag = TAG
            row.visibility = View.GONE
            val state = Entry(dialog, row, click, icon, dismiss, flip, helper)
            state.style()
            standard.accessibilityDelegate = null
            standard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            standard.isFocusable = false
            click.contentDescription = "分应用音量"
            click.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            click.isFocusable = true
            click.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                    info.isCheckable = false
                }
            }
            click.setOnClickListener { state.open() }
            dialog.addView(row, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, state.height))
            states[dialog] = WeakReference(state)
            row.addOnAttachStateChangeListener(state)
            if (row.isAttachedToWindow) state.onViewAttachedToWindow(row)
            state
        }
    }

    private class Entry(
        val dialog: ViewGroup, val row: ViewGroup, val click: View, val icon: ImageView,
        val dismiss: Method, val flip: Method, val helper: Any,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        var controller: WeakReference<Any>? = null
        var height = 0
        private val geometry = AppVolumeGeometry()
        private val lifecycle = AppVolumeLifecycle()
        private var wanted = false
        private var registered = false
        private var observer: ViewTreeObserver? = null
        private val main = Handler(Looper.getMainLooper())
        private val broadcasts = Handler(Looper.getMainLooper())
        private val audio = dialog.context.getSystemService(AudioManager::class.java)
        private val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                Reflect.attempt { update() }
            }
        }

        fun style() {
            val type = helper.javaClass
            val mode = type.getDeclaredMethod("setRingerMode", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val state = type.getDeclaredMethod("updateState").apply { isAccessible = true }
            // Advanced material skips unchanged state: initialize the copied blur surface first.
            mode.invoke(helper, true)
            state.invoke(helper)
            mode.invoke(helper, false)
            state.invoke(helper)
            type.getDeclaredMethod("onExpanded", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(helper, false, true)
            icon.setImageDrawable(EqualizerDrawable())
            icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            icon.visibility = View.VISIBLE
            height = click.layoutParams.height.coerceAtLeast(1) + dimension(dialog.context, "miui_volume_footer_margin_top", 10)
            row.layoutParams?.let { it.height = height; row.layoutParams = it }
        }

        fun show() {
            lifecycle.show()
            style()
            update()
        }

        fun beginDismiss() {
            lifecycle.dismiss()
            main.removeCallbacksAndMessages(null)
            click.isEnabled = false
        }

        fun finishDismiss() {
            lifecycle.detach()
            main.removeCallbacksAndMessages(null)
            wanted = false
            restoreMargin()
            row.visibility = View.GONE
        }

        fun restoreMargin() {
            val lp = dialog.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val target = geometry.restore(lp.topMargin)
            if (lp.topMargin != target) { lp.topMargin = target; dialog.layoutParams = lp }
        }

        fun update() {
            if (!lifecycle.dismissing) {
                val expanded = Reflect.callWith(dialog, "isExpanded") as? Boolean ?: true
                wanted = AppVolumePolicy.visible(lifecycle.visible && controller?.get() != null, expanded,
                    MediaPlayback.locked(dialog.context), MediaPlayback.active(dialog.context),
                    conflicts.any { dialog.findViewWithTag<View>(it) != null })
            }
            if (!wanted) lifecycle.cancelRequest()
            val lp = dialog.layoutParams as? FrameLayout.LayoutParams ?: return
            val vertical = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK
            val topAnchored = vertical == Gravity.TOP || vertical == 0 || lp.gravity == -1
            val target = geometry.refresh(lp.topMargin, height, wanted && topAnchored && flip.invoke(null) != true)
            if (!lifecycle.dismissing) row.visibility = if (geometry.offset > 0) View.VISIBLE else View.GONE
            click.isEnabled = wanted && !lifecycle.opening && !lifecycle.dismissing
            if (lp.topMargin != target) { lp.topMargin = target; dialog.layoutParams = lp }
        }

        override fun onViewAttachedToWindow(v: View) {
            if (registered) return
            observer = dialog.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            Reflect.attempt { audio?.registerAudioPlaybackCallback(callback, main) }
            registered = true
            Reflect.attempt { update() }
        }

        override fun onViewDetachedFromWindow(v: View) {
            finishDismiss()
            Reflect.attempt { audio?.unregisterAudioPlaybackCallback(callback) }
            Reflect.attempt { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            observer = null
            registered = false
        }

        override fun onPreDraw(): Boolean {
            Reflect.attempt {
                if (row.visibility == View.VISIBLE) {
                    if (MediaPlayback.locked(dialog.context)) {
                        lifecycle.cancelRequest()
                        if (lifecycle.dismissing) row.visibility = View.INVISIBLE else update()
                    }
                    val dnd = find(dialog, "dnd_layout")
                    val container = find(dialog, "volume_dialog_container")
                    val scale = dnd?.scaleX ?: 1f
                    row.scaleX = scale; row.scaleY = scale; row.alpha = dnd?.alpha ?: 1f
                    row.translationY = if (container == null) 0f else
                        (scale - 1f) * (row.top + row.height / 2f - container.top - container.height / 2f)
                }
            }
            return true
        }

        fun open() {
            Reflect.attempt { update() }
            if (!wanted || row.visibility != View.VISIBLE || !row.isAttachedToWindow || MediaPlayback.locked(dialog.context)) return
            val token = lifecycle.beginRequest() ?: return
            runCatching {
                dialog.context.startForegroundService(Intent().setClassName(AppVolumeSettings.PACKAGE, AppVolumeSettings.SERVICE)
                    .putExtra("streamType", 3).putExtra("flags", 0))
                click.isEnabled = false
                // Ordered broadcasts may never return while the destination is frozen.
                main.postDelayed({ if (lifecycle.accepts(token)) { lifecycle.cancelRequest(); Reflect.attempt { update() } } }, 2000)
                requestOpen(token, 0)
            }.onFailure { lifecycle.cancelRequest(); ModuleLog.error("app_volume start failed; native volume retained", it) }
        }

        private fun requestOpen(token: Int, attempt: Int) {
            main.postDelayed({
                if (!lifecycle.accepts(token) || !row.isAttachedToWindow || MediaPlayback.locked(dialog.context)) return@postDelayed
                runCatching {
                    dialog.context.sendOrderedBroadcast(Intent(AppVolumeSettings.ACTION).setPackage(AppVolumeSettings.PACKAGE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), null, object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (!lifecycle.accepts(token) || MediaPlayback.locked(context)) return
                            if (resultCode == Activity.RESULT_OK) {
                                lifecycle.cancelRequest()
                                // Controller clears timeouts/mShowing and supplies the required non-null callback.
                                Reflect.attempt { controller?.get()?.let { dismiss.invoke(it, 8) } }
                            } else if (attempt < 5 && row.isAttachedToWindow) {
                                requestOpen(token, attempt + 1)
                            } else {
                                lifecycle.cancelRequest()
                                Reflect.attempt { update() }
                                ModuleLog.warn("app_volume receiver unavailable or native panel refused; volume retained")
                            }
                        }
                    }, broadcasts, Activity.RESULT_CANCELED, null, null)
                }.onFailure { lifecycle.cancelRequest(); ModuleLog.error("app_volume request failed", it) }
            }, 150)
        }
    }

    private fun resource(context: Context, type: String, name: String): Int =
        context.resources.getIdentifier(name, type, PLUGIN).takeIf { it != 0 }
            ?: context.resources.getIdentifier(name, type, context.packageName)
    private fun find(root: View, name: String): View? = resource(root.context, "id", name).takeIf { it != 0 }?.let { root.findViewById(it) }
    private fun dimension(context: Context, name: String, fallback: Int): Int =
        resource(context, "dimen", name).takeIf { it != 0 }?.let { context.resources.getDimensionPixelSize(it) }
            ?: (context.resources.displayMetrics.density * fallback).toInt()

    private class EqualizerDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
            paint.strokeWidth = 2f
            for ((x, y) in listOf(5.5f to 13f, 12f to 8f, 18.5f to 15f)) {
                canvas.drawLine(x, 4.5f, x, 19.5f, paint)
                canvas.drawRoundRect(x - 2.1f, y - 2.8f, x + 2.1f, y + 2.8f, 1.8f, 1.8f, paint)
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
        @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
