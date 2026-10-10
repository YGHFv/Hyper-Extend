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
import android.content.res.Configuration
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
import android.widget.Toast
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.AppVolumeButtonPosition
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
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

    fun ownsExpandedPanel(dialog: View): Boolean =
        (dialog as? ViewGroup)?.let { states[it]?.get()?.panel?.active(it) } == true

    fun installPlugin(loader: ClassLoader, settings: HookSettings): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val position = AppVolumeButtonPosition.fromVariant(settings.string(AppVolumeSettings.POSITION))
        val dialog = Class.forName(PREFIX + "MiuiVolumeDialogView", false, loader)
        val controller = Class.forName(PREFIX + "VolumePanelViewController", false, loader)
        val showPanel = controller.getDeclaredMethod("showVolumePanelH", Int::class.javaPrimitiveType)
        val panel = SystemUiAppVolumePanel.install(loader)
        val show = dialog.getDeclaredMethod("showH", Runnable::class.java)
        val hide = dialog.getDeclaredMethod("dismissH", Boolean::class.javaPrimitiveType, Runnable::class.java)
        val finish = dialog.getDeclaredMethod("lambda\$dismissH\$0", Runnable::class.java)
        val apply = Class.forName("com.android.systemui.miui.ViewStateGroup", false, loader)
            .getDeclaredMethod("apply", ViewGroup::class.java)
        val layout = dialog.getDeclaredMethod("updateDialogViewLP")
        val flip = Class.forName("miui.systemui.util.FlipUtils", false, loader).getDeclaredMethod("isFlipTiny")
        val wideFold = Class.forName("miui.systemui.util.DeviceUtils", false, loader)
            .getDeclaredMethod("isWideFoldDevice").invoke(null) == true
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
        if (HookRuntime.hook(finish, "app_volume/finish/$suffix") { chain ->
                // Restore before the controller's completion callback reparents its native columns.
                Reflect.attempt { (chain.thisObject as? ViewGroup)?.let(panel::finishDismiss) }
                val result = chain.proceed()
                Reflect.attempt { states[chain.thisObject]?.get()?.finishDismiss() }
                result
            }) count++
        // Both synchronous expand and asynchronous pre-draw show pass through apply().
        if (HookRuntime.hook(apply, "app_volume/nativeState/$suffix") { chain ->
                val entry = states[chain.args[0]]?.get()
                Reflect.attempt { entry?.restoreNativeLayout() }
                val result = chain.proceed()
                Reflect.attempt { entry?.update() }
                Reflect.attempt { (chain.args[0] as? ViewGroup)?.let(panel::afterNativeState) }
                result
            }) count++
        if (HookRuntime.hook(layout, "app_volume/layout/$suffix") { chain ->
                val entry = states[chain.thisObject]?.get()
                Reflect.attempt { entry?.restoreNativeLayout() }
                val result = chain.proceed()
                Reflect.attempt { entry?.update() }
                Reflect.attempt { (chain.thisObject as? ViewGroup)?.let(panel::afterNativeState) }
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
                    host?.let { entry(it, panel, flip, wideFold, position)?.bindController(chain.thisObject!!) }
                }
                chain.proceed()
            }) count++
        return count + panel.hookCount
    }

    private fun entry(dialog: ViewGroup, panel: SystemUiAppVolumePanel, flip: Method, wideFold: Boolean,
        position: AppVolumeButtonPosition): Entry? {
        if (!panel.available()) return null
        if (dialog.context.packageManager.getPackageInfo(PLUGIN, 0).longVersionCode != 183022200L) return null
        states[dialog]?.get()?.let { return it }
        if (dialog.findViewWithTag<View>(TAG) != null) return null
        if (conflicts.any { dialog.findViewWithTag<View>(it) != null }) return null
        val host = Reflect.readField(dialog, "mRingerModeLayout") as? ViewGroup ?: return null
        if (Reflect.readField(host, "mNeedShowDialog") != true || flip.invoke(null) == true) return null
        return Reflect.attempt {
            val silent = find(host, "ringer_layout")
            val dnd = find(host, "dnd_layout")
            val buttons = silent?.parent as? LinearLayout
            if (position.inFooter && (buttons == null || dnd?.parent !== buttons)) return@attempt null
            val container = if (position.inFooter) buttons!! else dialog
            val index = AppVolumePlacementPolicy.insertionIndex(position,
                buttons?.indexOfChild(silent) ?: -1, buttons?.indexOfChild(dnd) ?: -1)
            if (index < 0) return@attempt null
            val anchor = if (position.targetsSilent) silent else dnd
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
            val state = Entry(dialog, row, click, icon, panel, flip, helper, wideFold, position, host,
                buttons, anchor, if (position.replacesNative) anchor else null)
            state.style()
            standard.accessibilityDelegate = null
            standard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            standard.isFocusable = false
            click.contentDescription = "多应用音量调节"
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
            container.addView(row, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, state.height))
            states[dialog] = WeakReference(state)
            row.addOnAttachStateChangeListener(state)
            if (row.isAttachedToWindow) state.onViewAttachedToWindow(row)
            state
        }
    }

    private class Entry(
        val dialog: ViewGroup, val row: ViewGroup, val click: View, val icon: ImageView,
        val panel: SystemUiAppVolumePanel, val flip: Method, val helper: Any, val wideFold: Boolean,
        val position: AppVolumeButtonPosition, val footer: ViewGroup, val buttons: LinearLayout?,
        val anchor: View?, val replaced: View?,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private var controller: WeakReference<Any>? = null
        private var motionSource: View? = null
        var height = 0
        private val geometry = AppVolumeGeometry()
        private val replacement = AppVolumeReplacementState()
        private val motion = AppVolumeEntryMotion()
        private val parentMotion = AppVolumeEntryMotion()
        private val footerHeight = AppVolumeFooterHeight()
        private val footerFrame = buttons?.parent as? View
        private val footerFrameLayout = View.OnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            footerHeight.onLayout(bottom - top)
        }
        private val lifecycle = AppVolumeLifecycle()
        private var wanted = false
        private var registered = false
        private var observer: ViewTreeObserver? = null
        private var nativeOpening = false
        private var pendingLayout: ViewTreeObserver.OnPreDrawListener? = null
        private var pendingSession: String? = null
        private var layoutTimeout: Runnable? = null
        private val main = Handler(Looper.getMainLooper())
        private val broadcasts = Handler(Looper.getMainLooper())
        private val audio = dialog.context.getSystemService(AudioManager::class.java)
        private val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                Reflect.attempt { update() }
            }
        }

        fun bindController(value: Any) {
            controller = WeakReference(value)
            motionSource = Reflect.readField(value, "mVolumeContentView") as? View
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
            val gap = if (position.replacesNative) 0 else dimension(dialog.context, "miui_volume_footer_margin_top", 10)
            height = click.layoutParams.height.coerceAtLeast(1) + gap
            // Existing rows have a spacer before DND; put the new gap after our row,
            // except below DND where it must precede the button instead.
            row.setPadding(0, if (position == AppVolumeButtonPosition.BELOW_DND) gap else 0, 0, 0)
            row.layoutParams?.let { it.height = height; row.layoutParams = it }
        }

        fun show() {
            cancelNativeOpen()
            lifecycle.show()
            style()
            update()
        }

        fun beginDismiss() {
            cancelNativeOpen()
            panel.beginDismiss(dialog)
            lifecycle.dismiss()
            main.removeCallbacksAndMessages(null)
            click.isEnabled = false
        }

        fun finishDismiss() {
            cancelNativeOpen()
            lifecycle.detach()
            main.removeCallbacksAndMessages(null)
            wanted = false
            restoreNativeLayout()
            row.visibility = View.GONE
            resetMotion()
        }

        fun restoreMargin() {
            val lp = dialog.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val target = geometry.restore(lp.topMargin)
            if (lp.topMargin != target) { lp.topMargin = target; dialog.layoutParams = lp }
        }

        private fun restoreReplacement() {
            replaced?.let { target ->
                val visibility = replacement.restore(target.visibility, View.GONE)
                if (target.visibility != visibility) target.visibility = visibility
            }
        }

        fun restoreNativeLayout() {
            restoreMargin()
            restoreReplacement()
            resizeFooter(null)
        }

        private fun resizeFooter(added: Int?) {
            if (!position.inFooter || position.replacesNative) return
            val frame = buttons?.parent as? View ?: return
            val params = frame.layoutParams ?: return
            val target = if (added == null) footerHeight.restore(params.height) else footerHeight.apply(params.height, added)
            if (params.height != target) { params.height = target; frame.layoutParams = params }
        }

        fun update() {
            if (!lifecycle.dismissing) {
                val expanded = Reflect.callWith(dialog, "isExpanded") as? Boolean ?: true
                wanted = AppVolumePolicy.visible(!SafeModeRuntime.blocked && lifecycle.visible && controller?.get() != null && motionSource != null &&
                    !nativeOpening && !panel.active(dialog), expanded,
                    MediaPlayback.locked(dialog.context), MediaPlayback.active(dialog.context),
                    conflicts.any { dialog.findViewWithTag<View>(it) != null })
            }
            if (!wanted) lifecycle.cancelRequest()
            updatePlacement()
        }

        private fun updatePlacement() {
            val lp = dialog.layoutParams as? FrameLayout.LayoutParams ?: return
            val vertical = lp.gravity and Gravity.VERTICAL_GRAVITY_MASK
            val topAnchored = vertical == Gravity.TOP || vertical == 0 || lp.gravity == -1
            // Ordinary landscape margins center the native height; include only half the added row.
            val centered = !wideFold && dialog.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val eligible = wanted && topAnchored && flip.invoke(null) != true && !SafeModeRuntime.blocked
            val visible: Boolean
            val target: Int
            if (!position.inFooter) {
                target = geometry.refresh(lp.topMargin, height, eligible, centered)
                visible = geometry.offset > 0
            } else {
                val added = if (position.replacesNative) 0 else height
                val base = lp.topMargin + geometry.offset
                val nativeHeight = dialog.height - footerHeight.measuredExtra
                val parentHeight = (dialog.parent as? View)?.height ?: 0
                val nativeVisible = anchor?.let { replacement.nativeVisibility(it.visibility, View.GONE) == View.VISIBLE } == true
                visible = eligible && !nativeOpening && !panel.active(dialog) &&
                    Reflect.callWith(dialog, "isExpanded") == false &&
                    footer.visibility == View.VISIBLE && buttons?.orientation == LinearLayout.VERTICAL &&
                    anchor?.parent === buttons && nativeVisible &&
                    AppVolumePlacementPolicy.footerFits(base, nativeHeight, added, parentHeight - lp.bottomMargin, centered)
                target = geometry.refresh(lp.topMargin, added, visible && centered, centered = true)
            }
            if (!lifecycle.dismissing) {
                row.visibility = if (visible) View.VISIBLE else View.GONE
                resizeFooter(if (visible) height else null)
                if (visible) replaced?.let {
                    val next = replacement.hide(it.visibility, View.GONE)
                    if (it.visibility != next) it.visibility = next
                }
                else restoreReplacement()
            }
            click.isEnabled = visible && !lifecycle.opening && !lifecycle.dismissing
            if (lp.topMargin != target) { lp.topMargin = target; dialog.layoutParams = lp }
        }

        override fun onViewAttachedToWindow(v: View) {
            if (registered) return
            observer = dialog.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            footerFrame?.addOnLayoutChangeListener(footerFrameLayout)
            Reflect.attempt { audio?.registerAudioPlaybackCallback(callback, main) }
            registered = true
            Reflect.attempt { update() }
        }

        override fun onViewDetachedFromWindow(v: View) {
            finishDismiss()
            Reflect.attempt { audio?.unregisterAudioPlaybackCallback(callback) }
            Reflect.attempt { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            observer = null
            footerFrame?.removeOnLayoutChangeListener(footerFrameLayout)
            registered = false
        }

        override fun onPreDraw(): Boolean {
            Reflect.attempt {
                if (SafeModeRuntime.blocked) {
                    finishDismiss()
                    return@attempt
                }
                if (position.inFooter && !lifecycle.dismissing) updatePlacement()
                if (row.visibility == View.VISIBLE) {
                    if (MediaPlayback.locked(dialog.context)) {
                        lifecycle.cancelRequest()
                        if (lifecycle.dismissing) row.visibility = View.INVISIBLE else update()
                    }
                    syncMotion()
                }
            }
            return true
        }

        private fun resetMotion() {
            row.scaleX = 1f; row.scaleY = 1f; row.alpha = 1f
            row.translationX = 0f; row.translationY = 0f
        }

        private fun syncMotion() {
            fun sample(start: View?, into: AppVolumeEntryMotion): Boolean {
                into.reset()
                var source = start
                while (source != null && source !== dialog) {
                    val parent = source.parent as? View ?: return false
                    into.include(
                        (source.left - parent.scrollX).toFloat(), (source.top - parent.scrollY).toFloat(),
                        source.pivotX, source.pivotY, source.scaleX, source.scaleY,
                        source.translationX, source.translationY, source.alpha,
                    )
                    source = parent
                }
                return source === dialog
            }
            if (!sample(motionSource, motion) || !sample(row.parent as? View, parentMotion)) { resetMotion(); return }
            // Use the visible capsule center, not the row center (which includes the bottom gap).
            row.pivotX = click.left + click.width / 2f
            row.pivotY = click.top + click.height / 2f
            val parent = row.parent as? View ?: return
            val residual = motion.relativeTo(parentMotion,
                row.left + row.pivotX - parent.scrollX, row.top + row.pivotY - parent.scrollY)
            if (residual == null) { resetMotion(); row.alpha = 0f; return }
            row.scaleX = residual.scaleX; row.scaleY = residual.scaleY; row.alpha = residual.alpha
            row.translationX = residual.translationX; row.translationY = residual.translationY
        }

        fun open() {
            Reflect.attempt { update() }
            if (!wanted || row.visibility != View.VISIBLE || !row.isAttachedToWindow || MediaPlayback.locked(dialog.context)) return
            val token = lifecycle.beginRequest() ?: return
            runCatching {
                dialog.context.startForegroundService(Intent().setClassName(AppVolumeSettings.PACKAGE, AppVolumeSettings.SERVICE)
                    .putExtra("streamType", 3).putExtra("flags", 0).putExtra(AppVolumeBridge.DATA_ONLY, true))
                click.isEnabled = false
                // Ordered broadcasts may never return while the destination is frozen.
                main.postDelayed({ if (lifecycle.accepts(token)) failOpen("timeout") }, 2000)
                requestOpen(token, 0)
            }.onFailure {
                ModuleLog.error("app_volume start failed; native volume retained", it)
                failOpen("service_start")
            }
        }

        private fun requestOpen(token: Int, attempt: Int) {
            main.postDelayed({
                if (!lifecycle.accepts(token) || !row.isAttachedToWindow || MediaPlayback.locked(dialog.context)) return@postDelayed
                runCatching {
                    val intent = AppVolumeBridge.request(AppVolumeBridge.QUERY)
                    dialog.context.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            val extras = getResultExtras(false)
                            val reason = AppVolumeBridge.failureReason(extras)
                            val data = if (resultCode == Activity.RESULT_OK) AppVolumeBridge.decode(extras) else null
                            if (!lifecycle.accepts(token) || MediaPlayback.locked(context)) {
                                data?.let { AppVolumeClient.end(context, it.token) }
                                return
                            }
                            if (data != null) {
                                ModuleLog.info("app_volume query accepted: apps=${data.apps.size}; requesting official expansion")
                                lifecycle.cancelRequest()
                                nativeOpening = true
                                restoreNativeLayout()
                                row.visibility = View.GONE
                                // Let layout remove the extra row before the native anchor is captured.
                                postOnNextLayout(data)
                            } else if ((reason == null || reason == "unavailable" || reason == "busy") && attempt < 5 && row.isAttachedToWindow) {
                                requestOpen(token, attempt + 1)
                            } else {
                                failOpen(reason ?: "unavailable")
                            }
                        }
                    }, broadcasts, Activity.RESULT_CANCELED, null, null)
                }.onFailure { ModuleLog.error("app_volume request failed", it); failOpen("request_error") }
            }, 150)
        }

        private fun failOpen(reason: String) {
            lifecycle.cancelRequest()
            ModuleLog.warn("app_volume open failed: $reason; native volume retained")
            Reflect.attempt { update() }
            if (!lifecycle.visible || lifecycle.dismissing || !row.isAttachedToWindow || MediaPlayback.locked(dialog.context)) return
            val message = when (reason) {
                "no_apps" -> "暂无可调节的播放应用（不支持共享 UID 或其他用户应用）"
                "disabled" -> "请先在音质音效中开启分应用音量功能"
                "native_busy" -> "请先关闭原音质音效面板后重试"
                "native_expand" -> "官方音量面板未能展开，请重试并查看模块日志"
                else -> "分应用音量暂不可用，请确认双作用域已启用并重启两个宿主"
            }
            Reflect.attempt { Toast.makeText(dialog.context, message, Toast.LENGTH_SHORT).show() }
        }

        private fun cancelNativeOpen() {
            pendingLayout?.let { if (dialog.viewTreeObserver.isAlive) dialog.viewTreeObserver.removeOnPreDrawListener(it) }
            layoutTimeout?.let(main::removeCallbacks)
            pendingSession?.let { AppVolumeClient.end(dialog.context, it) }
            pendingLayout = null
            pendingSession = null
            layoutTimeout = null
            nativeOpening = false
        }

        private fun postOnNextLayout(data: AppVolumeBridge.Snapshot) {
            pendingSession = data.token
            val listener = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (dialog.viewTreeObserver.isAlive) dialog.viewTreeObserver.removeOnPreDrawListener(this)
                    if (pendingLayout !== this || pendingSession != data.token) return true
                    pendingLayout = null
                    pendingSession = null
                    layoutTimeout?.let(main::removeCallbacks)
                    layoutTimeout = null
                    val target = controller?.get()
                    val accepted = runCatching {
                        lifecycle.visible && !lifecycle.dismissing && dialog.isAttachedToWindow &&
                            !MediaPlayback.locked(dialog.context) && target != null &&
                            panel.open(dialog, target, data, ended = { Reflect.attempt { update() } },
                                failed = { failOpen("native_expand") })
                    }.onFailure { ModuleLog.error("app_volume official expansion failed", it) }.getOrDefault(false)
                    nativeOpening = false
                    if (!accepted) {
                        AppVolumeClient.end(dialog.context, data.token)
                        failOpen("native_expand")
                    }
                    Reflect.attempt { update() }
                    return !accepted
                }
            }
            pendingLayout = listener
            dialog.viewTreeObserver.addOnPreDrawListener(listener)
            dialog.requestLayout()
            layoutTimeout = Runnable {
                if (pendingLayout === listener) {
                    cancelNativeOpen()
                    failOpen("layout_timeout")
                }
            }.also { main.postDelayed(it, 1000) }
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
