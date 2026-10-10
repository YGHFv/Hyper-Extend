/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.Choreographer
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Own columns, NOT controller.mColumns: native stream listeners must never see an application UID. */
internal class SystemUiAppVolumePanel private constructor(private val api: Api) {
    private val sessions = WeakHashMap<ViewGroup, WeakReference<Session>>()
    private val appColumns = WeakHashMap<Any, Boolean>()
    private var ready = false
    var hookCount = 0
        private set

    fun active(dialog: ViewGroup) = sessions[dialog]?.get() != null
    fun available() = ready
    fun afterNativeState(dialog: ViewGroup) { sessions[dialog]?.get()?.applyCompactLayout() }

    fun open(dialog: ViewGroup, controller: Any, data: AppVolumeBridge.Snapshot, ended: () -> Unit, failed: () -> Unit): Boolean {
        if (!ready || active(dialog) || !dialog.isAttachedToWindow ||
            dialog.context.packageManager.getPackageInfo(PLUGIN, 0).compatibleVersionCode != 183022200L ||
            api.expanded.invoke(dialog) == true || api.animating.invoke(dialog) == true) return false
        val session = Session(dialog, controller, data, ended, failed)
        sessions[dialog] = WeakReference(session)
        dialog.addOnAttachStateChangeListener(session)
        return runCatching { session.begin(); true }.getOrElse {
            ModuleLog.error("app_volume native expand refused; restoring official columns", it)
            session.restore()
            false
        }
    }

    fun beginDismiss(dialog: ViewGroup) { sessions[dialog]?.get()?.stop() }
    fun finishDismiss(dialog: ViewGroup) { sessions[dialog]?.get()?.restore() }

    private inner class Session(
        val dialog: ViewGroup, val controller: Any, val data: AppVolumeBridge.Snapshot, val ended: () -> Unit,
        val failed: () -> Unit,
    ) : View.OnAttachStateChangeListener {
        val motion = api.motion.get(dialog)!!
        val animator = api.animator.get(motion)!!
        val container = api.expandedContainer.get(animator) as ViewGroup
        val originalColumns = api.controllerColumns.get(controller) as List<*>
        private val mediaColumn = originalColumns.filterNotNull().single { api.stream.invoke(it) == 3 }
        private val mediaView = api.view.get(mediaColumn) as View
        private val mediaSlider = api.slider.get(mediaColumn) as SeekBar
        private val footer = api.footer.get(dialog) as View
        private val background = api.background.get(motion) as View
        private val content = api.content.get(animator) as ViewGroup
        private val shadow = api.shadow.get(motion) as View
        private val main = Handler(Looper.getMainLooper())
        private val slots = mutableListOf<Slot>()
        private val savedChildren = mutableListOf<View>()
        private val nativeParents = api.parents.get(controller)!!
        private val parked = LinearLayout(dialog.context)
        private val layoutParams = linkedMapOf<View, ViewGroup.LayoutParams>()
        private var nativeOrientation: Int? = null
        private val footerVisibility = footer.visibility
        private var footerRequested: Boolean? = null
        private var contentWidth = 0
        private var contentHeight = 0
        private var swapped = false
        private var stopped = false
        private var restored = false
        private var interactive = false
        private var page = 0
        private val levels = data.apps.associate { it.uid to it.level }.toMutableMap()
        private val edited = mutableSetOf<Int>()
        private val client = AppVolumeClient(dialog.context, data.token, ::refresh) { dismiss() }

        fun begin() {
            // Match processExpandTouch: keep the host's pre-show/offscreen close snapshot intact.
            check(api.fromCollapsed.get(animator) != null)
            api.captureFrom.invoke(animator, true)
            api.expand.invoke(controller)
            main.postDelayed({ if (!swapped && !stopped) rejectExpansion() }, 1800)
        }

        fun rejectExpansion() {
            if (restored || stopped) return
            restore()
            failed()
        }

        fun installColumns() {
            if (restored || stopped || swapped) return
            check(!MediaPlayback.locked(dialog.context))
            val nativeChildren = (0 until container.childCount).map(container::getChildAt)
            val visible = nativeChildren.filter { it.visibility == View.VISIBLE }
            check(mediaView in visible && container is LinearLayout)
            val capacity = AppVolumeExpandedLayout.appCapacity(data.apps.size, visible.size)
            check(capacity > 0)
            val notifySingle = api.notifySingle.getBoolean(controller)
            repeat(capacity) {
                val column = api.columnCtor.newInstance()
                appColumns[column] = true
                val viewId = View.generateViewId()
                val id = Int.MIN_VALUE + viewId
                try {
                    api.init.invoke(column, dialog.context, container, id, true, true, false)
                    api.columnExpanded.invoke(column, true)
                    api.size.invoke(column, true, notifySingle)
                    api.resource.invoke(column, true)
                    api.tint.invoke(column, true)
                    api.blend.invoke(column, false)
                    // This installs only the native visual listener, not VolumeSeekBarChangeListener.
                    api.initAnim.invoke(controller, column)
                    val view = api.view.get(column) as View
                    view.id = viewId
                    api.disableBlur.invoke(view, false)
                    val slot = Slot(column, view, id)
                    slots.add(slot)
                } catch (failure: Throwable) {
                    if (slots.none { it.column === column }) release(column, id)
                    throw failure
                }
            }
            slots.forEachIndexed { index, slot -> slot.bind(data.apps[index]) }
            savedChildren.addAll(nativeChildren)
            swapped = true
            listOf(dialog, content, container, container.parent as View, background, shadow, mediaView).forEach(::saveLayout)
            nativeOrientation = (dialog as LinearLayout).orientation
            // Native state updates/dynamic streams still use their own parent and their own list.
            api.parentExpanded.set(nativeParents, parked)
            nativeChildren.filter { it !== mediaView }.forEach { container.removeView(it); parked.addView(it) }
            slots.forEach { container.addView(it.view) }
            val views = listOf(mediaView) + slots.map { it.view }
            val gap = api.columnGap.invoke(null, dialog.context, true, true, notifySingle, 3) as Int
            val trailing = api.columnGap.invoke(null, dialog.context, true, true, notifySingle, if (notifySingle) 5 else 4) as Int
            val widths = views.mapIndexed { index, view ->
                view.layoutParams = LinearLayout.LayoutParams(view.layoutParams as LinearLayout.LayoutParams).apply {
                    leftMargin = 0
                    rightMargin = if (index == views.lastIndex) trailing else gap
                    weight = 0f
                }
                view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                contentHeight = maxOf(contentHeight, view.measuredHeight)
                view.measuredWidth
            }
            contentWidth = AppVolumeExpandedLayout.contentWidth(widths, gap, trailing)
            check(contentHeight > 0)
            api.setColumns.invoke(dialog, listOf(mediaColumn) + slots.map { it.column })
            applyCompactLayout()
            ModuleLog.info("app_volume official columns installed: media=1 apps=${slots.size} content=${contentWidth}x$contentHeight")
            container.requestLayout()
            main.postDelayed({ awaitExpansion(0) }, 32)
        }

        private fun saveLayout(view: View) {
            if (view in layoutParams) return
            layoutParams[view] = when (val params = view.layoutParams) {
                is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
                is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
                is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params)
                else -> ViewGroup.LayoutParams(params)
            }
        }

        fun hideFooter(requested: Boolean) {
            if (restored) return
            footerRequested = requested
            // Keep timer children measurable: the native animator divides by their measured sizes.
            // The explicit compact dialog bounds exclude this invisible row from the panel's size.
            footer.visibility = View.INVISIBLE
        }

        fun applyCompactLayout() {
            if (!swapped || restored || contentWidth == 0) return
            footer.visibility = View.INVISIBLE
            // Landscape normally places the footer beside the sliders; keep that hidden row below.
            (dialog as LinearLayout).orientation = LinearLayout.VERTICAL
            val padding = api.contentPadding.invoke(null, dialog.context, true) as Int
            val bgWidth = contentWidth + 2 * padding
            val bgHeight = contentHeight + 2 * padding
            val metrics = api.displayMetrics.invoke(motion) as DisplayMetrics
            val top = api.marginTop.invoke(null, dialog.context, true, true, metrics.heightPixels, bgHeight) as Int
            val end = if (api.horizontalCenter.invoke(motion) == true) (metrics.widthPixels - bgWidth) / 2 else
                (api.marginRight.invoke(null, dialog.context, true, true) as Int) + (api.insetRight.invoke(motion) as Int)
            fun size(view: View, width: Int, height: Int) {
                view.layoutParams = view.layoutParams.apply { this.width = width; this.height = height }
            }
            size(container, contentWidth, contentHeight)
            size(container.parent as View, contentWidth, contentHeight)
            size(content, contentWidth, contentHeight)
            (content.layoutParams as ViewGroup.MarginLayoutParams).apply { topMargin = padding; bottomMargin = 0 }
            size(dialog, contentWidth, contentHeight + padding)
            dialog.layoutParams = (dialog.layoutParams as ViewGroup.MarginLayoutParams).apply {
                topMargin = top; marginEnd = end + padding
            }
            background.layoutParams = (background.layoutParams as ViewGroup.MarginLayoutParams).apply {
                width = bgWidth; height = bgHeight; topMargin = top; marginEnd = end
            }
            // The official pre-draw now samples the compact background AND its matching shadow.
            api.updateShadow.invoke(motion)
        }

        private fun awaitExpansion(attempt: Int) {
            if (stopped || restored) return
            runCatching {
                if (api.animatorExpanded.getBoolean(animator) && api.animating.invoke(dialog) == false) {
                    interactive = true
                    slots.forEach { it.enable() }
                    ModuleLog.info("app_volume official expansion complete")
                    client.start()
                } else if (attempt < 80) main.postDelayed({ awaitExpansion(attempt + 1) }, 32)
                else dismiss()
            }.onFailure { dismiss() }
        }

        private fun refresh(value: AppVolumeBridge.Snapshot) {
            if (stopped || restored) return
            // Do not reassign a slider under a finger, or change the animator's cached column count.
            if (MediaPlayback.locked(dialog.context) || value.apps.map { it.uid to it.packageName }.toSet() !=
                data.apps.map { it.uid to it.packageName }.toSet()) { dismiss(); return }
            val current = value.apps.associateBy { it.uid }
            value.apps.filter { it.uid !in edited }.forEach { levels[it.uid] = it.level }
            slots.forEach { slot ->
                if (!slot.tracking) slot.app?.takeIf { it.uid !in edited }?.let { app -> current[app.uid]?.let { slot.level(it.level) } }
            }
        }

        private fun nextPage() {
            if (!interactive || stopped || mediaSlider.isPressed || slots.any { it.tracking } || api.animating.invoke(dialog) == true) return
            page = (page + 1) % AppVolumeBridgePolicy.pageCount(data.apps.size, slots.size)
            val start = AppVolumeExpandedLayout.pageStart(page, data.apps.size, slots.size)
            slots.forEachIndexed { index, slot -> slot.bind(data.apps[start + index]); slot.enable() }
            api.timeout.invoke(controller)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            interactive = false
            main.removeCallbacksAndMessages(null)
            slots.forEach {
                it.enable()
                Reflect.attempt { api.resetSlider.invoke(it.slider) }
            }
            client.close()
        }

        private fun dismiss() {
            stop()
            // Use the same controller dismissal as timeout/back/outside touch, never hide a second window.
            Reflect.attempt { api.dismiss.invoke(controller, 1) }
        }

        fun restore(cancelAnimation: Boolean = false) {
            if (restored) return
            restored = true
            stop()
            if (swapped || cancelAnimation) {
                if (cancelAnimation) Reflect.attempt { api.clean.invoke(animator) }
                // Folme's final property update can leave one queued frame after its completion callback.
                Reflect.attempt {
                    (api.choreographer.get(animator) as Choreographer)
                        .removeFrameCallback(api.frameCallback.get(animator) as Choreographer.FrameCallback)
                    api.updateScheduled.setBoolean(animator, false)
                }
            }
            if (swapped) {
                slots.forEach { (it.view.parent as? ViewGroup)?.removeView(it.view) }
                (mediaView.parent as? ViewGroup)?.removeView(mediaView)
                parked.addView(mediaView)
                // Dynamic native streams may have been added while our columns were displayed.
                api.parentExpanded.set(nativeParents, container)
                // STREAM_MUSIC must return to its original position, not the end of the parked list.
                originalColumns.filterNotNull().map { api.view.get(it) as View }.filter { it.parent === parked }.forEach {
                    parked.removeView(it); container.addView(it)
                }
                while (parked.childCount > 0) {
                    val view = parked.getChildAt(0)
                    parked.removeView(view)
                    container.addView(view)
                }
                // Also recover a child detached immediately before a failed addView transaction.
                savedChildren.filter { it.parent == null && originalColumns.any { column ->
                    column != null && api.view.get(column) === it
                } }.forEach(container::addView)
                api.setColumns.invoke(dialog, originalColumns)
                api.cachedColumns.set(animator, null)
            }
            layoutParams.forEach { (view, params) -> view.layoutParams = params }
            layoutParams.clear()
            nativeOrientation?.let { (dialog as LinearLayout).orientation = it }
            slots.forEach { release(it.column, it.id) }
            slots.clear()
            savedChildren.clear()
            dialog.removeOnAttachStateChangeListener(this)
            sessions.remove(dialog)
            footerRequested?.let { api.updateFooter.invoke(dialog, it) } ?: run { footer.visibility = footerVisibility }
            ended()
        }

        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { restore(cancelAnimation = true) }

        private inner class Slot(val column: Any, val view: View, val id: Int) : SeekBar.OnSeekBarChangeListener {
            val slider = api.slider.get(column) as SeekBar
            private val icon = api.icon.get(column) as ImageView
            private val label = api.label.get(column) as TextView
            private val progress = api.progress.get(column)!!
            var app: AppVolumeBridge.App? = null
            var tracking = false

            init {
                slider.max = AppVolumeBridgePolicy.MAX_LEVEL
                api.progressMax.invoke(progress, AppVolumeBridgePolicy.MAX_LEVEL)
                slider.setOnSeekBarChangeListener(this)
                slider.isEnabled = false
                icon.imageTintList = null
                icon.setOnClickListener { runCatching { nextPage() }.onFailure { dismiss() } }
                label.setOnClickListener { runCatching { nextPage() }.onFailure { dismiss() } }
            }

            fun bind(value: AppVolumeBridge.App?) {
                app = value
                val pages = AppVolumeBridgePolicy.pageCount(data.apps.size, slots.size.coerceAtLeast(1))
                val name = value?.label?.ifBlank { value.packageName }.orEmpty()
                slider.contentDescription = name
                icon.contentDescription = name
                icon.tooltipText = name
                icon.setImageDrawable(if (value == null) null else value.icon
                    ?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    ?.let { BitmapDrawable(dialog.resources, it) } ?: dialog.context.packageManager.defaultActivityIcon)
                label.text = if (pages > 1) "${page + 1}/$pages" else ""
                label.contentDescription = "${page + 1}/$pages, next page"
                label.visibility = if (pages > 1 && interactive) View.VISIBLE else View.GONE
                level(value?.let { levels[it.uid] } ?: 0)
            }

            fun enable() {
                slider.isEnabled = interactive && !stopped && app != null
                val pages = AppVolumeBridgePolicy.pageCount(data.apps.size, slots.size.coerceAtLeast(1))
                icon.isEnabled = interactive && !stopped && pages > 1
                label.isEnabled = icon.isEnabled
                label.visibility = if (pages > 1 && interactive && !stopped) View.VISIBLE else View.GONE
                slider.importantForAccessibility = if (app != null) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            fun level(value: Int) {
                slider.progress = value
                api.ratio.invoke(column)
                api.progressTo.invoke(progress, false, slider)
            }

            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                runCatching {
                    api.ratio.invoke(column)
                    api.progressTo.invoke(progress, fromUser, slider)
                    if (fromUser && interactive && !stopped) app?.let {
                        levels[it.uid] = value
                        edited.add(it.uid)
                        client.write(it.uid, value)
                        api.timeout.invoke(controller)
                    }
                }.onFailure { dismiss() }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                tracking = true
                // Keep native stream tracking false: do not publish our ratio to system stream flows.
                Reflect.attempt { api.timeout.invoke(controller) }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                tracking = false
                if (interactive && !stopped) app?.let { client.write(it.uid, slider.progress, immediate = true) }
                Reflect.attempt { api.timeout.invoke(controller) }
            }
        }
    }

    private fun release(column: Any, id: Int) {
        Reflect.attempt { (api.slider.get(column) as SeekBar).setOnSeekBarChangeListener(null) }
        Reflect.attempt { (api.icon.get(column) as View).setOnClickListener(null) }
        Reflect.attempt { (api.label.get(column) as View).setOnClickListener(null) }
        Reflect.attempt { api.release.invoke(column) }
        appColumns.remove(column)
        // initColumn creates a per-stream StateFlow even for our private view IDs.
        Reflect.attempt { (api.ratioFlows.get(null) as? MutableMap<*, *>)?.remove(id) }
    }

    private fun install(): SystemUiAppVolumePanel {
        val suffix = Integer.toHexString(System.identityHashCode(api.controller.classLoader))
        if (HookRuntime.hook(api.updateExpanded, "app_volume/nativeColumns/$suffix") { chain ->
                val dialog = Reflect.readField(chain.thisObject, "mVolumeView") as? ViewGroup
                val session = sessions[dialog]?.get()
                if (chain.args[0] == false) Reflect.attempt { session?.restore() }
                val result = chain.proceed()
                if (chain.args[0] == true) runCatching { session?.installColumns() }.onFailure {
                    ModuleLog.error("app_volume column swap failed; official panel retained", it)
                    Reflect.attempt { session?.rejectExpansion() }
                }
                result
            }) hookCount++
        if (HookRuntime.hook(api.reinit, "app_volume/nativeReinit/$suffix") { chain ->
                val dialog = Reflect.readField(chain.thisObject, "mVolumeView") as? ViewGroup
                Reflect.attempt { sessions[dialog]?.get()?.restore(cancelAnimation = true) }
                chain.proceed()
            }) hookCount++
        if (HookRuntime.hook(api.iconColor, "app_volume/appIcon/$suffix") { chain ->
                if (appColumns.containsKey(chain.thisObject)) null else chain.proceed()
            }) hookCount++
        if (HookRuntime.hookAfter(api.updateFooter, "app_volume/appFooter/$suffix") { chain, original ->
                sessions[chain.thisObject]?.get()?.hideFooter(chain.args[0] == true)
                original
            }) hookCount++
        if (HookRuntime.hookAfter(api.updateBackgroundSize, "app_volume/appBackground/$suffix") { chain, original ->
                val dialog = api.motionDialog.get(chain.thisObject) as? ViewGroup
                sessions[dialog]?.get()?.applyCompactLayout()
                original
            }) hookCount++
        // updateExpandedH(ZZ) is a tiny forwarding overload and commonly inlined in callbacks.
        var deoptimized = true
        for (method in api.controller.declaredMethods) {
            deoptimized = HookRuntime.deoptimize(method, "app_volume/nativeController") && deoptimized
        }
        for (method in api.columnMethods) {
            deoptimized = HookRuntime.deoptimize(method, "app_volume/nativeColumn") && deoptimized
        }
        for (method in api.iconObservers) {
            deoptimized = HookRuntime.deoptimize(method, "app_volume/nativeIconObserver") && deoptimized
        }
        for (method in api.motionMethods) {
            deoptimized = HookRuntime.deoptimize(method, "app_volume/nativeMotion") && deoptimized
        }
        ready = hookCount == 5 && deoptimized
        return this
    }

    private class Api(loader: ClassLoader) {
        val controller = Class.forName(PREFIX + "VolumePanelViewController", false, loader)
        private val dialog = Class.forName(PREFIX + "MiuiVolumeDialogView", false, loader)
        private val motionType = Class.forName(PREFIX + "MiuiVolumeDialogMotion", false, loader)
        private val animatorType = Class.forName(PREFIX + "VolumeExpandCollapsedAnimator", false, loader)
        private val column = Class.forName(PREFIX + "VolumeColumn", false, loader)
        private val resources = Class.forName(PREFIX + "MiuiVolumeDialogRes", false, loader)
        private val columnResources = Class.forName(PREFIX + "VolumeColumnRes", false, loader)
        val motionMethods = motionType.declaredMethods
        val columnMethods = column.declaredMethods
        val iconObservers = (1..10).flatMap { index ->
            Class.forName(PREFIX + "VolumeColumn\$special\$\$inlined\$observable\$$index", false, loader).declaredMethods.toList()
        }
        private val bool = Boolean::class.javaPrimitiveType!!
        private val int = Int::class.javaPrimitiveType!!
        private fun method(type: Class<*>, name: String, vararg args: Class<*>) =
            type.getDeclaredMethod(name, *args).apply { isAccessible = true }
        private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val motion = field(dialog, "mMotion")
        val animator = field(motionType, "mVolumeExpandCollapsedAnimator")
        val expandedContainer = field(animatorType, "volumeDialogColumns")
        val content = field(animatorType, "volumeDialogContent")
        val footer = field(dialog, "mRingerModeLayout")
        val motionDialog = field(motionType, "mVolumeView")
        val background = field(motionType, "mExpandBgView")
        val shadow = field(motionType, "mShadowView")
        val fromCollapsed = field(animatorType, "fromCollapsedContentValue")
        val animatorExpanded = field(animatorType, "mExpanded")
        val cachedColumns = field(animatorType, "volumeColumnList")
        val controllerColumns = field(controller, "mColumns")
        val parents = field(controller, "mVolumeColumns")
        val parentExpanded = field(parents.type, "mColumnsExpanded")
        val notifySingle = field(controller, "mIsNotifySingle")
        val captureFrom = method(animatorType, "calculateFromViewValues", bool)
        val clean = method(animatorType, "clean")
        val choreographer = field(animatorType, "choreographer")
        val frameCallback = field(animatorType, "frameCallback")
        val updateScheduled = field(animatorType, "updateScheduled")
        val expand = method(controller, "onExpandClicked")
        val dismiss = method(controller, "dismissH", int)
        val timeout = method(controller, "rescheduleTimeoutH")
        val updateFooter = method(dialog, "updateFooterVisibility", bool)
        val updateBackgroundSize = method(motionType, "updateExpandBgSize")
        val updateShadow = method(motionType, "updateShadowState")
        val displayMetrics = method(motionType, "provideDisplayMetrics")
        val horizontalCenter = method(motionType, "shouldHorizontalCenter")
        val insetRight = method(motionType, "getInsetRight")
        val contentPadding = method(resources, "getBgWithContentPadding", Context::class.java, bool)
        val marginTop = method(resources, "getMarginTop", Context::class.java, bool, bool, int, int)
        val marginRight = method(resources, "getMarginRight", Context::class.java, bool, bool)
        val columnGap = method(columnResources, "getMarginRight", Context::class.java, bool, bool, bool, int)
        val updateExpanded = method(controller, "updateExpandedH", bool, bool, bool)
        val reinit = method(controller, "reInit")
        val setColumns = method(dialog, "setVolumeColumns", List::class.java)
        val expanded = dialog.getMethod("isExpanded")
        val animating = method(dialog, "isAnimating")
        val columnCtor = column.getDeclaredConstructor()
        val stream = method(column, "getStream")
        val init = method(column, "initColumn", Context::class.java, ViewGroup::class.java, int, bool, bool, bool)
        val size = method(column, "setSize", bool, bool)
        val initAnim = method(controller, "initAnimListener", column)
        val columnExpanded = method(column, "setExpanded", bool)
        val resource = method(column, "setSliderResource", bool)
        val tint = method(column, "setSliderTintColorList", bool)
        val blend = method(column, "setSliderBlendColor", bool)
        val ratio = method(column, "updateSliderRatio")
        val iconColor = method(column, "updateIconColor")
        val release = method(column, "release")
        val ratioFlows = field(column, "SliderRatioRatioFlowMap")
        val view = field(column, "view")
        val slider = field(column, "slider")
        val resetSlider = method(Class.forName(PREFIX + "MiuiVolumeSeekBar", false, loader), "resetView")
        val icon = field(column, "icon")
        val label = field(column, "superVolume")
        val progress = field(column, "progressView")
        val progressTo = method(progress.type, "toProgressWithAnim", bool, SeekBar::class.java)
        val progressMax = method(progress.type, "setMaxLevel", int)
        val disableBlur: Method = Class.forName(PREFIX + "widget.VolumeBlurFrameLayout", false, loader)
            .getMethod("setBlurEnabled", bool)
    }

    companion object {
        private const val PLUGIN = "miui.systemui.plugin"
        private const val PREFIX = "com.android.systemui.miui.volume."
        fun install(loader: ClassLoader) = SystemUiAppVolumePanel(Api(loader)).install()
    }
}
