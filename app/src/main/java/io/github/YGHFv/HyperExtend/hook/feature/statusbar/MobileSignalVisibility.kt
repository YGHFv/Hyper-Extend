/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.View
import android.view.ViewGroup
import io.github.YGHFv.HyperExtend.core.MobileSignalSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.function.Consumer

/** OS4 only: replace before bind starts collecting, then update the same host-owned flow. */
internal class MobileSignalVisibility private constructor(
    private val policy: MobileSignalPolicy,
    private val factory: StateFlowFactory,
    private val pair: Constructor<*>,
    private val collect: Method,
) {
    private val main = Handler(Looper.getMainLooper())
    private val records = WeakHashMap<Any, Record>()
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private val active = mutableSetOf<Binding>()
    private val warned = mutableSetOf<String>()
    private var context: Context? = null
    private var networkRegistered = false
    private var receiverRegistered = false
    private var observerRegistered = false
    private var subscriptionsRegistered = false

    private class Record(val subId: Int, val original: Any, val relay: HostStateFlow, var value: Any)

    private inner class Binding(view: View, val record: Record, val collection: Any) : View.OnAttachStateChangeListener {
        val view = WeakReference(view)
        override fun onViewAttachedToWindow(v: View) {
            active.add(this)
            startWatching(v.context.applicationContext ?: v.context)
            refresh()
        }
        override fun onViewDetachedFromWindow(v: View) {
            active.remove(this)
            if (active.isEmpty()) stopWatching()
        }
        fun dispose() {
            view.get()?.removeOnAttachStateChangeListener(this)
            active.remove(this)
            Reflect.callWith(collection, "dispose")
            if (active.isEmpty()) stopWatching()
        }
    }

    private val refreshTask = Runnable { refresh() }
    private fun requestRefresh() {
        main.removeCallbacks(refreshTask)
        main.post(refreshTask)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestRefresh()
    }
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = requestRefresh()
    }
    private val subscriptions = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = requestRefresh()
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = requestRefresh()
        override fun onLost(network: Network) = requestRefresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = requestRefresh()
    }

    private fun bind(view: View, vm: Any) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            warnOnce("bind is not on main thread; preserving system visibility")
            return
        }
        val field = Reflect.findField(vm.javaClass, "isVisible") ?: return
        val current = field.get(vm) ?: return
        val record = records[vm] ?: run {
            val interactor = Reflect.readField(vm, "iconInteractor") ?: return
            val subId = Reflect.readField(interactor, "subId") as? Int ?: return
            val value = Reflect.callWith(current, "getValue") ?: return
            if (!validPair(value)) {
                warnOnce("isVisible does not contain Pair<Boolean, Boolean>; skipped")
                return
            }
            val relay = factory.mutable(value) ?: return
            if (!field.type.isInstance(relay.readonly)) return
            Record(subId, current, relay, value)
        }
        val old = bindings[view]?.get()
        if (old?.record === record && current === record.relay.readonly) return

        // Keep the original stream alive for default/unavailable-state fallback. The host helper
        // cancels collection on detach and restarts it on attach, without a polling timer.
        val collection = collect.invoke(null, view, record.original, Consumer<Any> { value ->
            if (validPair(value)) {
                record.value = value
                refresh()
            }
        }) ?: return
        val binding = Binding(view, record, collection)
        try {
            apply(record, view.context)
            field.set(vm, record.relay.readonly)
        } catch (failure: Throwable) {
            binding.dispose()
            throw failure
        }
        old?.dispose()
        records[vm] = record
        bindings[view] = WeakReference(binding)
        view.addOnAttachStateChangeListener(binding)
        if (view.isAttachedToWindow) binding.onViewAttachedToWindow(view)
    }

    private fun validPair(value: Any): Boolean = pair.declaringClass.isInstance(value) &&
        Reflect.callWith(value, "getFirst") is Boolean && Reflect.callWith(value, "getSecond") is Boolean

    private fun refresh() {
        val ctx = context ?: return
        // Several views (status bar/control center) can share a VM; update every distinct relay.
        active.map { it.record }.distinct().forEach { record ->
            runCatching { apply(record, ctx) }.onFailure { warnOnce("visibility refresh failed: ${it.javaClass.simpleName}") }
        }
    }

    private fun apply(record: Record, ctx: Context) {
        val slot = readState { SubscriptionManager.getSlotIndex(record.subId) }
        val airplane = if (policy.mode != 0) readState {
            Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON) != 0
        } else null
        val defaultData = if (policy.mode >= 2) readState { SubscriptionManager.getDefaultDataSubscriptionId() } else null
        val transports = if (policy.mode in 1..2) readState {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: error("ConnectivityManager missing")
            val network = cm.activeNetwork
            if (network == null) false to false else {
                val caps = cm.getNetworkCapabilities(network) ?: error("Capabilities unavailable")
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) to caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
        } else null
        val visible = policy.overrideVisibility(record.subId, slot, defaultData, airplane, transports?.first, transports?.second)
        val value = if (visible == null) record.value else pair.newInstance(visible, visible)
        if (!record.relay.set(value)) warnOnce("could not update visibility StateFlow")
    }

    private fun <T> readState(block: () -> T): T? = try {
        block()
    } catch (failure: Throwable) {
        warnOnce("state query failed (${failure.javaClass.simpleName}); preserving original when unknown")
        null
    }

    private fun startWatching(ctx: Context) {
        context = ctx
        if (!receiverRegistered) receiverRegistered = watch {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")
                addAction("android.intent.action.USER_SWITCHED")
            }
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        }
        if (!subscriptionsRegistered) subscriptionsRegistered = watch {
            val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: error("SubscriptionManager missing")
            sm.addOnSubscriptionsChangedListener(ctx.mainExecutor, subscriptions)
        }
        if (policy.mode != 0 && !observerRegistered) observerRegistered = watch {
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON), false, observer,
            )
        }
        if (policy.mode in 1..2 && !networkRegistered) networkRegistered = watch {
            val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: error("ConnectivityManager missing")
            cm.registerDefaultNetworkCallback(network, main)
        }
    }

    private fun watch(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (failure: Throwable) {
        warnOnce("state listener unavailable: ${failure.javaClass.simpleName}")
        false
    }

    private fun stopWatching() {
        val ctx = context ?: return
        if (receiverRegistered) runCatching { ctx.unregisterReceiver(receiver) }
        if (observerRegistered) runCatching { ctx.contentResolver.unregisterContentObserver(observer) }
        if (subscriptionsRegistered) runCatching {
            ctx.getSystemService(SubscriptionManager::class.java)?.removeOnSubscriptionsChangedListener(subscriptions)
        }
        if (networkRegistered) runCatching {
            ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(network)
        }
        receiverRegistered = false
        observerRegistered = false
        subscriptionsRegistered = false
        networkRegistered = false
        main.removeCallbacks(refreshTask)
        context = null
    }

    private fun warnOnce(message: String) {
        if (warned.add(message)) ModuleLog.warn("$FEATURE: $message")
    }

    companion object {
        private const val FEATURE = "status_bar_mobile/visibility"
        private const val VM = "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconVMImpl"
        private const val BINDER = "com.android.systemui.statusbar.pipeline.mobile.ui.binder.MiuiMobileIconBinder"

        fun install(loader: ClassLoader, settings: HookSettings): Int {
            val policy = MobileSignalPolicy(
                MobileSignalSettings.mode(settings.string(MobileSignalSettings.MODE)),
                settings.isOn(MobileSignalSettings.HIDE_SIM_1), settings.isOn(MobileSignalSettings.HIDE_SIM_2),
            )
            if (!policy.enabled) return 0
            return try {
                val vm = Reflect.loadClass(loader, VM) ?: error("VM missing")
                check(Reflect.findField(vm, "isVisible")?.type?.name == "kotlinx.coroutines.flow.StateFlow")
                check(Reflect.findField(vm, "iconInteractor") != null)
                val binder = Reflect.loadClass(loader, BINDER) ?: error("binder missing")
                val bind = Reflect.findMethods(binder, "bind", 4).firstOrNull {
                    it.parameterTypes.map { type -> type.name } == listOf(
                        ViewGroup::class.java.name,
                        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel",
                        "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconViewModel",
                        "com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger",
                    )
                } ?: error("bind signature changed")
                val adapter = Reflect.loadClass(loader, "com.android.systemui.util.kotlin.JavaAdapterKt") ?: error("adapter missing")
                val collect = Reflect.findMethods(adapter, "collectFlow", 3).firstOrNull {
                    it.parameterTypes.map { type -> type.name } == listOf(
                        View::class.java.name, "kotlinx.coroutines.flow.Flow", Consumer::class.java.name,
                    )
                }?.also { it.isAccessible = true } ?: error("collectFlow signature changed")
                val pair = Reflect.loadClass(loader, "kotlin.Pair")?.getConstructor(Any::class.java, Any::class.java)
                    ?: error("Pair missing")
                val factory = StateFlowFactory(loader)
                check(factory.isAvailable)
                val controller = MobileSignalVisibility(policy, factory, pair, collect)
                val installed = HookRuntime.hook(bind, "$FEATURE/MiuiMobileIconBinder#bind") { chain ->
                    val view = chain.args[0] as? View
                    val model = chain.args[2]
                    if (view != null && vm.isInstance(model)) {
                        runCatching { controller.bind(view, model!!) }
                            .onFailure { controller.warnOnce("bind skipped: ${it.javaClass.simpleName}") }
                    }
                    chain.proceed()
                }
                if (installed) 1 else 0
            } catch (failure: Throwable) {
                ModuleLog.warn("$FEATURE: OS4 targets unavailable (${failure.message}); skipped")
                0
            }
        }
    }
}
