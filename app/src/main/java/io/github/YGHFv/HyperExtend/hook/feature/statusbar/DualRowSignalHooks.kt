/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * DualRowSignalHookV visuals; OS4 binding/lifecycle implementation is local.
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.BroadcastReceiver
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import io.github.YGHFv.HyperExtend.core.DualRowSignalSettings
import io.github.YGHFv.HyperExtend.core.MobileSignalSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.roundToInt

internal class DualRowSignalHooks private constructor(
    private val loader: ClassLoader,
    private val vmInterface: Class<*>,
    private val collect: Method,
    private val factory: StateFlowFactory,
    private val hidden: Any,
    settings: HookSettings,
) {
    private val main = Handler(Looper.getMainLooper())
    private val style = DualRowSignalSettings.style(settings.string(DualRowSignalSettings.STYLE))
    private val scale = settings.number(DualRowSignalSettings.SCALE, 100).coerceIn(70, 140) / 100f
    private val left = settings.number(DualRowSignalSettings.LEFT, 0).coerceIn(-8, 8) / 2f
    private val right = settings.number(DualRowSignalSettings.RIGHT, 0).coerceIn(-8, 8) / 2f
    private val vertical = settings.number(DualRowSignalSettings.VERTICAL, 0).coerceIn(-40, 40) / 10f
    private val bound = WeakHashMap<View, java.lang.ref.WeakReference<Binding>>()
    private val active = mutableSetOf<Binding>()
    private var context: Context? = null
    private var sims = emptyList<DualRowSignalPolicy.Sim>()
    private var defaultData = -1
    private var airplane: Boolean? = null
    private var receiverRegistered = false
    private var subscriptionsRegistered = false
    private var resources: android.content.res.Resources? = null
    private val assets = mutableMapOf<String, Drawable.ConstantState>()
    private var refreshing = false
    private var failed = false
    private val getters = mutableMapOf<Pair<Class<*>, String>, Method>()

    private fun get(target: Any, name: String): Any? = getters.getOrPut(target.javaClass to name) {
        target.javaClass.getMethod(name).also { it.isAccessible = true }
    }.invoke(target)

    private fun protected(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            failed = true
            SafeModeRuntime.trip("Hook 执行异常：双排移动网络图标 (${failure.javaClass.simpleName})")
            ModuleLog.error("$FEATURE: restoring native signal views", failure)
            active.toList().forEach { runCatching { it.restore() } }
        }
    }

    private inner class Binding(
        val root: ViewGroup, val image: ImageView, val group: View,
        val vm: Any, val id: Int, val visibility: Any, val relay: HostStateFlow,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        var value: Any = Reflect.callWith(visibility, "getValue")!!
        var satellite: Boolean? = null
        var tint: Any? = null
        val collections = mutableListOf<Any>()
        var collectionGeneration = 0
        var observer: ViewTreeObserver? = null
        var native: Drawable? = null
        var composite: DualDrawable? = null
        var signature: List<Any>? = null
        var width: Int? = null
        var ownedWidth: Int? = null
        val leftPadding = DualRowSignalPaddingEdge()
        val rightPadding = DualRowSignalPaddingEdge()
        var nativeDescription: CharSequence? = null
        var ownedDescription: CharSequence? = null
        var resourceId: Int? = null
        var level: Int? = null
        var cellular = false

        // A per-binding facade avoids changing a VM shared by status bar and control center.
        // All methods except visibility still execute on the original host VM.
        val facade: Any = MobileViewModelFacade.create(loader, vmInterface, vm, mapOf("isVisible" to relay.readonly))

        override fun onViewAttachedToWindow(v: View) = protected {
            if (!active.add(this)) return@protected
            collectionGeneration++
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            startWatching(root.context.applicationContext ?: root.context)
            observe(visibility) { value = it }
            val original = originalViewModel(vm)
            val interactor = Reflect.readField(original, "iconInteractor")
            val flow = interactor?.let { Reflect.readField(it, "sateliteEnable") }
            if (flow != null) observe(flow) { satellite = it as? Boolean }
            Reflect.readField(original, "vmProvider")?.let { provider -> observe(provider) {
                cellular = it.javaClass.name == PREFIX + "viewmodel.MiuiCellularIconVM"
            } }
            Reflect.callWith(vm, "getSignalIconId")?.let { signal -> observe(signal) {
                resourceId = it as? Int
                level = resourceId?.let { id -> runCatching { root.resources.getResourceEntryName(id) }.getOrNull() }
                    ?.let(DualRowSignalPolicy::level)
            } }
            refresh()
        }

        private fun observe(flow: Any, consume: (Any) -> Unit) {
            val generation = collectionGeneration
            val handle = collect.invoke(null, root, flow, Consumer<Any> { next -> protected {
                if (generation != collectionGeneration || this !in active) return@protected
                consume(next)
                refresh()
            } }) ?: error("collectFlow returned no disposable")
            collections.add(handle)
        }

        override fun onViewDetachedFromWindow(v: View) = protected {
            active.remove(this)
            collectionGeneration++
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            collections.forEach { Reflect.callWith(it, "dispose") }
            collections.clear()
            satellite = null
            resourceId = null
            level = null
            cellular = false
            restore()
            if (active.isEmpty()) stopWatching() else refresh()
        }

        override fun onPreDraw(): Boolean {
            if (active.firstOrNull { it.observer === observer } === this) protected { refresh() }
            return true
        }

        fun dispose() {
            root.removeOnAttachStateChangeListener(this)
            onViewDetachedFromWindow(root)
        }

        fun signal(): DualRowSignalPolicy.Signal {
            return DualRowSignalPolicy.Signal(id, get(value, "getFirst") == true,
                if (cellular) level else null, satellite)
        }

        fun rememberNative() {
            if (image.drawable !== composite) native = image.drawable
            if (ownedWidth != null && image.layoutParams.width != ownedWidth) width = image.layoutParams.width
            if (ownedDescription == null || root.contentDescription != ownedDescription) nativeDescription = root.contentDescription
        }

        fun restore(visibility: Boolean = true) {
            rememberNative()
            if (visibility) check(relay.set(value))
            if (image.drawable === composite && composite != null) image.setImageDrawable(native)
            width?.let { original ->
                if (image.layoutParams.width == ownedWidth) image.layoutParams = image.layoutParams.apply { width = original }
            }
            setHorizontalPadding(leftPadding.restore(group.paddingLeft), rightPadding.restore(group.paddingRight))
            if (ownedDescription != null && root.contentDescription == ownedDescription) root.contentDescription = nativeDescription
            composite = null
            signature = null
            width = null
            ownedWidth = null
            ownedDescription = null
        }

        private fun setHorizontalPadding(left: Int, right: Int) {
            if (group.paddingLeft != left || group.paddingRight != right) {
                group.setPadding(left, group.paddingTop, right, group.paddingBottom)
            }
        }

        fun render(rows: DualRowSignalPolicy.Rows, lower: Binding) {
            fun fallback() { restore(); lower.restore() }
            val triple = tint?.let { get(it, "getValue") } ?: return fallback()
            val useTint = get(triple, "getFirst") as? Boolean ?: return fallback()
            val light = get(triple, "getSecond") as? Boolean ?: return fallback()
            val color = get(triple, "getThird") as? Int ?: return fallback()
            rememberNative()
            val density = root.resources.displayMetrics.density
            val key = listOf(rows.upperLevel, rows.lowerLevel, useTint, light, color, density)
            if (signature != key) {
                val upperIcon = asset(1, rows.upperLevel, useTint, light)
                val lowerIcon = asset(2, rows.lowerLevel, useTint, light)
                val layers = LayerDrawable(arrayOf(upperIcon, lowerIcon))
                if (useTint && style != "theme") layers.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                composite = DualDrawable(layers, scale, vertical * density)
                signature = key
            }
            if (width == null) width = image.layoutParams.width
            val base = width!!.takeIf { it > 0 } ?: image.height.takeIf { it > 0 } ?: return fallback()
            val targetWidth = (base * scale).roundToInt().coerceAtLeast(1)
            ownedWidth = targetWidth
            if (image.layoutParams.width != targetWidth) image.layoutParams = image.layoutParams.apply { width = targetWidth }
            setHorizontalPadding(
                leftPadding.apply(group.paddingLeft, (left * density).roundToInt()),
                rightPadding.apply(group.paddingRight, (right * density).roundToInt()),
            )
            if (image.drawable !== composite) image.setImageDrawable(composite)
            // Keep native localized descriptions, including the second SIM that is now hidden.
            lower.rememberNative()
            val description = listOfNotNull(nativeDescription, lower.nativeDescription).filter { it.isNotBlank() }.joinToString("; ")
            if (description.isNotEmpty()) {
                ownedDescription = description
                if (root.contentDescription != description) root.contentDescription = description
            }
            check(relay.set(value))
            check(lower.relay.set(hidden))
        }
    }

    private fun prepare(root: ViewGroup, vm: Any): Binding? {
        if (Looper.myLooper() != Looper.getMainLooper() || failed) return null
        bound.remove(root)?.get()?.dispose()
        val interactor = Reflect.readField(originalViewModel(vm), "iconInteractor") ?: return null
        val id = Reflect.readField(interactor, "subId") as? Int ?: return null
        val visibility = Reflect.callWith(vm, "isVisible") ?: return null
        val value = Reflect.callWith(visibility, "getValue") ?: return null
        if (Reflect.callWith(value, "getFirst") !is Boolean || Reflect.callWith(value, "getSecond") !is Boolean) return null
        fun view(name: String): View? = root.resources.getIdentifier(name, "id", "com.android.systemui")
            .takeIf { it != 0 }?.let { root.findViewById(it) }
        val image = view("mobile_signal") as? ImageView ?: return null
        val group = view("mobile_group") ?: return null
        if (image.layoutParams.width <= 0) return null
        // Load from the installed module, never resolve module R IDs in host Resources.
        if (resources == null) {
            resources = root.context.createPackageContext("io.github.YGHFv.HyperExtend", 0).resources
            for (slot in 1..2) for (level in 0..4) for (mode in 0..2) asset(slot, level, mode == 2, mode != 1)
        }
        val relay = factory.mutable(value) ?: return null
        return Binding(root, image, group, vm, id, visibility, relay)
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            val all = active.toList()
            if (failed || SafeModeRuntime.blocked) { all.forEach { it.restore() }; return }
            // Sibling roots represent one icon container. Never pair across displays/locations.
            all.groupBy { it.root.parent }.forEach { (parent, bindings) ->
                val rows = if (parent == null) null else DualRowSignalPolicy.rows(sims, bindings.map { it.signal() }, defaultData, airplane)
                val upper = rows?.let { pair -> bindings.singleOrNull { it.id == pair.upper } }
                val lower = rows?.let { pair -> bindings.singleOrNull { it.id == pair.lower } }
                if (rows == null || upper == null || lower == null || upper.tint == null || lower.tint == null) {
                    bindings.forEach { it.restore() }
                } else {
                    // Restore the previous anchor before switching default-data SIM.
                    lower.restore(visibility = false)
                    upper.render(rows, lower)
                }
            }
        } finally { refreshing = false }
    }

    private fun asset(slot: Int, level: Int, useTint: Boolean, light: Boolean): Drawable {
        val suffix = when { style == "theme" -> if (light) "" else "_dark"; useTint -> "_tint"; light -> ""; else -> "_dark" }
        val name = "hyperextend_dual_${slot}_${DualRowSignalPolicy.assetLevel(level)}$suffix" + if (style.isEmpty()) "" else "_$style"
        val res = resources ?: error("module resources unavailable")
        val state = assets.getOrPut(name) {
            val id = res.getIdentifier(name, "drawable", "io.github.YGHFv.HyperExtend")
            check(id != 0) { "missing dual-row asset $name" }
            res.getDrawable(id, null).constantState ?: error("asset has no constant state")
        }
        return state.newDrawable(res).mutate()
    }

    private val stateTask = Runnable { protected { readState(); refresh() } }
    private fun requestState() { main.removeCallbacks(stateTask); main.post(stateTask) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestState()
    }
    private val subscriptions = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = requestState()
    }

    // The module does not request telephony access; SystemUI's grant is checked before use.
    @SuppressLint("MissingPermission")
    private fun readState() {
        val ctx = context ?: return
        // Permission/service failures are compatibility fallbacks, not protected-hook failures.
        sims = if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList
                    ?.map { DualRowSignalPolicy.Sim(it.subscriptionId, it.simSlotIndex) }.orEmpty()
            } catch (_: SecurityException) { emptyList() }
            catch (_: Exception) { emptyList() }
        } else emptyList()
        defaultData = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrDefault(-1)
        airplane = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0 }.getOrNull()
        if (!receiverRegistered || !subscriptionsRegistered) sims = emptyList()
    }

    private fun startWatching(ctx: Context) {
        if (context != null) return
        context = ctx
        receiverRegistered = runCatching {
            ctx.registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.intent.action.USER_SWITCHED")
            }, Context.RECEIVER_EXPORTED)
            true
        }.getOrDefault(false)
        subscriptionsRegistered = runCatching {
            val manager = ctx.getSystemService(SubscriptionManager::class.java) ?: error("no subscription service")
            if (Build.VERSION.SDK_INT >= 30) manager.addOnSubscriptionsChangedListener(ctx.mainExecutor, subscriptions)
            else {
                @Suppress("DEPRECATION")
                manager.addOnSubscriptionsChangedListener(subscriptions)
            }
            true
        }.getOrDefault(false)
        readState()
    }

    private fun stopWatching() {
        val ctx = context ?: return
        if (receiverRegistered) runCatching { ctx.unregisterReceiver(receiver) }
        if (subscriptionsRegistered) runCatching { ctx.getSystemService(SubscriptionManager::class.java)?.removeOnSubscriptionsChangedListener(subscriptions) }
        receiverRegistered = false
        subscriptionsRegistered = false
        main.removeCallbacks(stateTask)
        context = null
        sims = emptyList()
        airplane = null
    }

    private class DualDrawable(private val layers: Drawable, private val scale: Float, private val offset: Float) : Drawable() {
        override fun draw(canvas: Canvas) {
            val side = bounds.height() * scale
            val save = canvas.save()
            canvas.translate(bounds.exactCenterX() - side / 2, bounds.exactCenterY() - side / 2 + offset)
            layers.setBounds(0, 0, side.roundToInt(), side.roundToInt())
            layers.draw(canvas)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { layers.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { layers.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    companion object {
        private const val FEATURE = DualRowSignalSettings.FEATURE
        private const val PREFIX = "com.android.systemui.statusbar.pipeline.mobile.ui."
        fun originalViewModel(value: Any): Any = MobileViewModelFacade.original(value)

        fun install(loader: ClassLoader, settings: HookSettings): Int {
            if (!DualRowSignalSettings.compatible(settings.isOn("status_bar_mobile"), settings.string(MobileSignalSettings.MODE),
                    settings.isOn(MobileSignalSettings.HIDE_SIM_1), settings.isOn(MobileSignalSettings.HIDE_SIM_2))) {
                ModuleLog.info("$FEATURE: incompatible mobile visibility settings; preserving native layout")
                return 0
            }
            val controller: DualRowSignalHooks
            val bind: Method
            try {
                val vm = Reflect.loadClass(loader, PREFIX + "viewmodel.MiuiMobileIconViewModel") ?: error("VM interface missing")
                check(vm.isInterface)
                val impl = Reflect.loadClass(loader, PREFIX + "viewmodel.MiuiMobileIconVMImpl") ?: error("OS4 VM missing")
                check(Reflect.findField(impl, "iconInteractor") != null)
                val binder = Reflect.loadClass(loader, PREFIX + "binder.MiuiMobileIconBinder") ?: error("binder missing")
                bind = Reflect.findMethods(binder, "bind", 4).firstOrNull {
                    it.parameterTypes.map { p -> p.name } == listOf(ViewGroup::class.java.name,
                        PREFIX + "viewmodel.LocationBasedMobileViewModel", vm.name, PREFIX + "MobileViewLogger")
                } ?: error("bind signature changed")
                val adapter = Reflect.loadClass(loader, "com.android.systemui.util.kotlin.JavaAdapterKt") ?: error("adapter missing")
                val collect = Reflect.findMethods(adapter, "collectFlow", 3).firstOrNull {
                    it.parameterTypes.map { p -> p.name } == listOf(View::class.java.name, "kotlinx.coroutines.flow.Flow", Consumer::class.java.name)
                }?.also { it.isAccessible = true } ?: error("collectFlow missing")
                val pair = Reflect.loadClass(loader, "kotlin.Pair")!!.getConstructor(Any::class.java, Any::class.java)
                val factory = StateFlowFactory(loader)
                check(factory.isAvailable)
                controller = DualRowSignalHooks(loader, vm, collect, factory, pair.newInstance(false, false), settings)
            } catch (failure: Throwable) {
                ModuleLog.warn("$FEATURE: OS4 contract unavailable (${failure.message}); skipped")
                return 0
            }
            return if (HookRuntime.hook(bind, "$FEATURE/MiuiMobileIconBinder#bind") { chain ->
                val root = chain.args[0] as? ViewGroup
                val vm = chain.args[2]
                if (root != null && root.context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode != 202602260L) {
                    return@hook chain.proceed()
                }
                val binding = if (root == null || vm == null) null else controller.prepare(root, vm)
                if (binding == null) chain.proceed() else {
                    val args = chain.args.toMutableList().apply { set(2, binding.facade) }
                    val result = chain.proceed(args.toTypedArray())
                    binding.tint = result?.let { Reflect.readField(it, "\$tintLightColorFlow") }
                    controller.bound[root] = java.lang.ref.WeakReference(binding)
                    root!!.addOnAttachStateChangeListener(binding)
                    if (root.isAttachedToWindow) binding.onViewAttachedToWindow(root)
                    result
                }
            }) 1 else 0
        }
    }
}
