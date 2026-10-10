/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * MobileTypeSingle2Hook settings; local OS4 per-binding relay and restoration.
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.graphics.Typeface
import android.os.Looper
import android.telephony.ServiceState
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.MobileTypeDisplaySettings as Config
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.function.Consumer
import kotlin.math.roundToInt

internal class MobileTypeDisplayHooks private constructor(
    private val loader: ClassLoader, private val contract: Class<*>,
    private val collect: Method, private val inService: Method,
    private val factory: StateFlowFactory, settings: HookSettings,
) {
    private val mode = Config.mode(settings.string(Config.MODE))
    private val separate = settings.isOn(Config.SEPARATE)
    private val onLeft = settings.isOn(Config.LEFT)
    private val bold = settings.isOn(Config.BOLD)
    private val size = settings.number(Config.SIZE, 27).coerceIn(18, 40) / 2f
    private val left = settings.number(Config.LEFT_MARGIN, 0).coerceIn(0, 16) / 2f
    private val right = settings.number(Config.RIGHT_MARGIN, 0).coerceIn(0, 16) / 2f
    private val vertical = settings.number(Config.VERTICAL, 0).coerceIn(-40, 40) / 10f
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()

    private inner class Binding(
        val root: ViewGroup, val group: LinearLayout, val text: TextView, val signal: View,
        val delegate: Any, val source: Any, val interactor: Any, val origin: Any,
        val originalFlows: List<Any>, val relays: List<HostStateFlow>,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        val facade = MobileViewModelFacade.create(loader, contract, delegate,
            GETTERS.zip(relays.map { it.readonly }).toMap())
        private val values = arrayOfNulls<Boolean>(3)
        private var name: String? = null
        private var cellular: Boolean? = null
        private var service: Boolean? = null
        private var satellite: Boolean? = null
        private var visible: Boolean? = null
        private var wifi: Boolean? = null
        private var data: Boolean? = null
        private var attached = false
        private var generation = 0
        private var failed = false
        private val handles = mutableListOf<Any>()
        private var observer: ViewTreeObserver? = null
        private val fontSize = MobileTypeOwnedValue<Float>()
        private val typeface = MobileTypeOwnedValue<Typeface?>()
        private val leftPadding = MobileTypeOwnedValue<Int>()
        private val rightPadding = MobileTypeOwnedValue<Int>()
        private val translation = MobileTypeOwnedValue<Float>()
        private val order = MobileTypeOwnedValue<List<View>>()

        private fun protect(block: () -> Unit) {
            try { block() } catch (failure: Throwable) {
                failed = true
                SafeModeRuntime.trip("Hook failed: mobile type display (${failure.javaClass.simpleName})")
                ModuleLog.error("${Config.FEATURE}: restoring native display", failure)
                runCatching { restore() }
            }
        }

        private fun observe(flow: Any, consume: (Any?) -> Unit) {
            val token = generation
            val handle = collect.invoke(null, root, flow, Consumer<Any?> { value -> protect {
                if (!attached || token != generation) return@protect
                consume(value)
                refresh()
            } }) ?: error("collectFlow returned no disposable")
            handles.add(handle)
        }

        override fun onViewAttachedToWindow(v: View) = protect {
            if (attached) return@protect
            attached = true
            generation++
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            // CREATED-level collections stay alive even if a dual-row facade hides this root.
            originalFlows.forEachIndexed { index, flow -> observe(flow) { values[index] = it as? Boolean } }
            observe(Reflect.callWith(source, "getShowName")!!) { name = it as? String }
            observe(Reflect.readField(source, "vmProvider")!!) {
                cellular = it?.javaClass?.name?.let { name -> name == PREFIX + "viewmodel.MiuiCellularIconVM" }
            }
            observe(Reflect.readField(interactor, "serviceState")!!) { service = inService.invoke(null, it) as? Boolean }
            observe(Reflect.readField(interactor, "sateliteEnable")!!) { satellite = it as? Boolean }
            observe(Reflect.callWith(source, "isVisible")!!) { visible = Reflect.callWith(it, "getFirst") as? Boolean }
            observe(Reflect.readField(interactor, "wifiAvailable")!!) { wifi = it as? Boolean }
            observe(Reflect.callWith(origin, "isDataConnected")!!) { data = it as? Boolean }
            refresh()
        }

        override fun onViewDetachedFromWindow(v: View) = protect {
            attached = false
            generation++
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            handles.forEach { Reflect.callWith(it, "dispose") }
            handles.clear()
            restore()
            values.fill(null)
            name = null; cellular = null; service = null; satellite = null
            visible = null; wifi = null; data = null
        }

        fun dispose() { root.removeOnAttachStateChangeListener(this); onViewDetachedFromWindow(root) }
        override fun onPreDraw(): Boolean { protect { refresh() }; return true }

        private fun native(): MobileTypeDisplayPolicy.Native? {
            val small = values[0] ?: return null
            val large = values[1] ?: return null
            val special = values[2] ?: return null
            return MobileTypeDisplayPolicy.Native(small, large, special)
        }

        private fun restoreFlows() {
            values.forEachIndexed { index, value -> if (value != null) check(relays[index].set(value)) }
        }

        private fun refresh() {
            if (!attached || failed || SafeModeRuntime.blocked) { restore(); return }
            val state = MobileTypeDisplayPolicy.State(native(), name, cellular, service, satellite, visible, wifi, data)
            val result = MobileTypeDisplayPolicy.resolve(mode, separate, state)
            if (result == null) { restore(); return }
            if (separate && mode != 3) style() else restoreStyle()
            listOf(result.small, result.large, result.special).forEachIndexed { i, value -> check(relays[i].set(value)) }
        }

        private fun children() = (0 until group.childCount).map { group.getChildAt(it) }

        private fun setOrder(desired: List<View>) {
            val current = children()
            if (current == desired || current.toSet() != desired.toSet()) return
            // Reorder without remove/add: native onViewRemoved would destroy its animation tag.
            desired.forEach(group::bringChildToFront)
            group.requestLayout()
            group.invalidate()
        }

        private fun style() {
            if (text.parent !== group || signal.parent !== group) { restoreStyle(); return }
            val density = text.resources.displayMetrics.density
            val nextSize = fontSize.apply(text.textSize) { size * density }
            if (text.textSize != nextSize) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, nextSize)
            val nextFace = if (bold) typeface.apply(text.typeface) { Typeface.create(it, Typeface.BOLD) }
                else typeface.restore(text.typeface)
            if (text.typeface != nextFace) text.typeface = nextFace
            setPadding(leftPadding.apply(text.paddingLeft) { it + (left * density).roundToInt() },
                rightPadding.apply(text.paddingRight) { it + (right * density).roundToInt() })
            val nextY = translation.apply(text.translationY) { it + vertical * density }
            if (text.translationY != nextY) text.translationY = nextY
            setOrder(order.apply(children()) { baseline ->
                MobileTypeDisplayPolicy.order(baseline, text, signal, onLeft, group.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            })
        }

        private fun setPadding(left: Int, right: Int) {
            if (text.paddingLeft != left || text.paddingRight != right) text.setPadding(left, text.paddingTop, right, text.paddingBottom)
        }

        private fun restoreStyle() {
            val oldSize = fontSize.restore(text.textSize)
            if (text.textSize != oldSize) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, oldSize)
            val oldFace = typeface.restore(text.typeface)
            if (text.typeface != oldFace) text.typeface = oldFace
            setPadding(leftPadding.restore(text.paddingLeft), rightPadding.restore(text.paddingRight))
            val oldY = translation.restore(text.translationY)
            if (text.translationY != oldY) text.translationY = oldY
            setOrder(order.restore(children()))
        }

        private fun restore() { restoreStyle(); restoreFlows() }
    }

    private fun prepare(root: ViewGroup, delegate: Any): Binding? {
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        bindings.remove(root)?.get()?.dispose()
        val source = MobileViewModelFacade.original(delegate)
        val interactor = Reflect.readField(source, "iconInteractor") ?: return null
        val origin = Reflect.callWith(source, "getOriginIconInteractor") ?: return null
        if (Reflect.readField(source, "vmProvider") == null || Reflect.readField(interactor, "serviceState") == null ||
            Reflect.readField(interactor, "sateliteEnable") == null || Reflect.readField(interactor, "wifiAvailable") == null) return null
        fun view(name: String): View? = root.resources.getIdentifier(name, "id", "com.android.systemui")
            .takeIf { it != 0 }?.let { root.findViewById(it) }
        val group = view("mobile_group") as? LinearLayout ?: return null
        val text = view("mobile_type_single") as? TextView ?: return null
        val signal = view("mobile_signal_container") ?: return null
        if (text.parent !== group || signal.parent !== group) return null
        val flows = GETTERS.map { Reflect.callWith(delegate, it) ?: return null }
        val relays = flows.map { factory.mutable(Reflect.callWith(it, "getValue") as? Boolean ?: false) ?: return null }
        return Binding(root, group, text, signal, delegate, source, interactor, origin, flows, relays)
    }

    companion object {
        private const val PREFIX = "com.android.systemui.statusbar.pipeline.mobile.ui."
        private val GETTERS = listOf("getMobileTypeVisible", "getMobileTypeSingleVisible", "getShowSpecial5GIcon")

        fun install(loader: ClassLoader, settings: HookSettings): Int {
            if (Config.mode(settings.string(Config.MODE)) == 0 && !settings.isOn(Config.SEPARATE)) return 0
            val controller: MobileTypeDisplayHooks
            val bind: Method
            try {
                val contract = Reflect.loadClass(loader, PREFIX + "viewmodel.MiuiMobileIconViewModel") ?: error("VM missing")
                check(contract.isInterface)
                GETTERS.forEach { check(contract.getMethod(it).returnType.name == "kotlinx.coroutines.flow.Flow") }
                val binder = Reflect.loadClass(loader, PREFIX + "binder.MiuiMobileIconBinder") ?: error("binder missing")
                bind = Reflect.findMethods(binder, "bind", 4).single { method ->
                    method.parameterTypes.map { it.name } == listOf(ViewGroup::class.java.name,
                        PREFIX + "viewmodel.LocationBasedMobileViewModel", contract.name, PREFIX + "MobileViewLogger")
                }
                val adapter = Reflect.loadClass(loader, "com.android.systemui.util.kotlin.JavaAdapterKt") ?: error("adapter missing")
                val collect = Reflect.findMethods(adapter, "collectFlow", 3).single { method ->
                    method.parameterTypes.map { it.name } == listOf(View::class.java.name, "kotlinx.coroutines.flow.Flow", Consumer::class.java.name)
                }.also { it.isAccessible = true }
                val utils = Reflect.loadClass(loader, "com.miui.systemui.statusbar.mobile.MobileUtils") ?: error("service helper missing")
                val inService = utils.getDeclaredMethod("isInService", ServiceState::class.java).also { it.isAccessible = true }
                val factory = StateFlowFactory(loader)
                check(factory.isAvailable)
                controller = MobileTypeDisplayHooks(loader, contract, collect, inService, factory, settings)
            } catch (failure: Throwable) {
                ModuleLog.warn("${Config.FEATURE}: OS4 contract unavailable (${failure.message}); skipped")
                return 0
            }
            return if (HookRuntime.hook(bind, "${Config.FEATURE}/MiuiMobileIconBinder#bind") { chain ->
                val root = chain.args[0] as? ViewGroup
                val delegate = chain.args[2]
                if (root == null || delegate == null || root.context.packageManager
                        .getPackageInfo("com.android.systemui", 0).longVersionCode != 202602260L) return@hook chain.proceed()
                val binding = controller.prepare(root, delegate) ?: return@hook chain.proceed()
                val result = chain.proceed(chain.args.toMutableList().apply { set(2, binding.facade) }.toTypedArray())
                controller.bindings[root] = WeakReference(binding)
                root.addOnAttachStateChangeListener(binding)
                if (root.isAttachedToWindow) binding.onViewAttachedToWindow(root)
                result
            }) 1 else 0
        }
    }
}
