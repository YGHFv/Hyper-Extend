/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.os.UserHandle
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface NativeToggleBackend {
    val feature: String
    val spec: String
    val label: String
    val iconName: String
    val settingsComponent: ComponentName?
    fun supported(context: Context): Boolean
    fun read(context: Context): Boolean?
    fun write(context: Context, enabled: Boolean)
}

/** Shared host tile lifecycle; backend supplies only the confirmed native state operation. */
internal class NativeToggleTileHooks(private val backend: NativeToggleBackend) {
    @Volatile var ready = false
        private set
    private val t = GmsTileTargets
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(4),
            { task -> Thread(task, "HyperExtend-${backend.spec}").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    private inner class Api(val loader: ClassLoader) {
        val settingsMethods = runCatching {
            GmsSettingsTargets.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        }.getOrNull()
        val starterField = runCatching { GmsSettingsTargets.starter.let { (owner, name, type) ->
            StatusIconHost.field(loader, owner, name, type)
        } }.getOrNull()
        val methods = t.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val fields = t.fields.associate { (owner, name, type) -> (owner to name) to StatusIconHost.field(loader, owner, name, type) }
        fun field(owner: String, name: String) = fields.getValue(owner to name)
        val tile = Class.forName(t.TILE, false, loader).also { type ->
            require(type.isInterface && FiveGTileContract.accepts(type.methods.map {
                "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.name }}):${it.returnType.name}"
            }))
        }
        val state = Class.forName(t.BOOLEAN, false, loader).getConstructor()
        val icon = Class.forName("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon", false, loader).getConstructor(Drawable::class.java)
        val sequence = Class.forName("com.android.internal.logging.InstanceIdSequence", false, loader).getConstructor(Int::class.javaPrimitiveType)
        fun context(host: Any) = methods.getValue(t.userContext).invoke(host) as Context
        fun lazy(value: Any?) = methods.getValue(t.lazyGet).invoke(value)
    }
    fun install(loader: ClassLoader): Int {
        val api = Api(loader)
        if (!t.callers.all { HookRuntime.deoptimize(api.methods.getValue(it), "native_toggle/${backend.spec}/factory-caller") }) return 0
        ready = HookRuntime.hook(api.methods.getValue(t.create), "native_toggle/${backend.spec}/factory") { chain ->
            if (chain.args[0] != backend.spec) return@hook chain.proceed()
            if (!enabled()) return@hook null
            runCatching {
                val factory = requireNotNull(chain.thisObject)
                val host = requireNotNull(api.lazy(api.field(t.FACTORY, "qsHostLazy").get(factory)))
                val context = api.context(host)
                if (!supported(context)) return@runCatching null
                val falsing = requireNotNull(api.lazy(api.field(t.FACTORY, "falsingManager").get(factory)))
                Tile(api, host, context, falsing, api.starterField?.get(factory)).proxy
            }.onFailure { ModuleLog.error("native_toggle/${backend.spec} factory unavailable; no native fallback action", it) }.getOrNull()
        }
        return if (ready) 1 else 0
    }
    private fun enabled() = ready && !SafeModeRuntime.blocked && HookRuntime.settings().isOn(backend.feature)

    @SuppressLint("NewApi")
    private fun supported(context: Context): Boolean {
        val user = context.getSystemService(UserManager::class.java) ?: return false
        return user.isSystemUser && !user.isManagedProfile &&
            backend.supported(context)
    }
    private inner class Tile(val api: Api, val host: Any, context: Context, val falsing: Any, val starterLazy: Any?) {
        private val main = Handler(Looper.getMainLooper())
        private val callbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val listeners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val generation = AtomicInteger()
        private val busy = AtomicBoolean()
        @Volatile private var destroyed = false
        @Volatile private var listening = false
        @Volatile private var currentUser = 0
        @Volatile private var last: Boolean? = null
        private val instanceId = api.methods.getValue(t.nextId).invoke(api.sequence.newInstance(1 shl 20))
        private val icon = context.createPackageContext("io.github.YGHFv.HyperExtend", 0).let { module ->
            val id = module.resources.getIdentifier(backend.iconName, "drawable", module.packageName)
            require(id != 0)
            api.icon.newInstance(requireNotNull(module.getDrawable(id)).mutate())
        }
        @Volatile private var current = state(null)
        val proxy: Any = Proxy.newProxyInstance(api.loader, arrayOf(api.tile)) { self, method, args ->
            runCatching {
                when (method.name) {
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "HyperExtend-${backend.spec}"
                    "getState" -> api.methods.getValue(t.copy).invoke(current)
                    "getTileLabel" -> backend.label
                    "getTileSpec", "getMetricsSpec" -> backend.spec
                    "getInstanceId" -> instanceId
                    "getMetricsCategory" -> 0
                    "getCurrentTileUser" -> currentUser
                    "isAvailable" -> !destroyed && currentUser == 0 && enabled()
                    "isListening" -> listening
                    "isDestroyed" -> destroyed
                    "isTileReady" -> true
                    "isConnected" -> false
                    "getDetailsViewModel" -> if (args?.size == 1) {
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as java.util.function.Consumer<Any?>).accept(null); false
                    } else null
                    "getDetailAdapter", "showDetail", "setDetailListening", "setTileSpec" -> null
                    "longClick" -> {
                        val at = SystemClock.uptimeMillis()
                        val gen = generation.get()
                        main.post { guarded { openSettings(gen, at) } }; null
                    }
                    "populate" -> args?.get(0)
                    "destroy" -> {
                        destroyed = true; generation.incrementAndGet()
                        main.post { listening = false; callbacks.clear(); listeners.clear(); main.removeCallbacks(tick) }; null
                    }
                    "userSwitch" -> {
                        currentUser = args?.get(0) as? Int ?: -1; generation.incrementAndGet()
                        main.post { guarded { publish(null); refresh() } }; null
                    }
                    "click", "secondaryClick" -> {
                        val expected = last
                        val at = SystemClock.uptimeMillis()
                        val gen = generation.get()
                        if (expected != null) main.post { guarded {
                            if (valid(gen, at) && api.methods.getValue(t.falseTap).invoke(falsing, 1) == false)
                                refresh(expected, gen, at)
                        } }
                        null
                    }
                    else -> {
                        if (method.name == "setListening" && args?.get(1) == false) generation.incrementAndGet()
                        main.post { guarded { event(method.name, args.orEmpty()) } }; null
                    }
                }
            }.getOrElse {
                ModuleLog.error("native_toggle/${backend.spec} callback failed; no retry", it)
                when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null }
            }
        }
        private fun guarded(block: () -> Unit) {
            if (!destroyed) runCatching(block).onFailure { ModuleLog.error("native_toggle/${backend.spec} event failed", it) }
        }
        private fun allowed(age: Long = 0): Boolean = runCatching {
            val context = api.context(host)
            NativeTogglePolicy.canAct(enabled(), SafeModeRuntime.blocked, currentUser == 0 && supported(context),
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false,
                !destroyed && listening, age)
        }.getOrDefault(false)
        private fun valid(gen: Int, at: Long) = generation.get() == gen && allowed(SystemClock.uptimeMillis() - at)
        private fun settingsIntent(): Intent? = backend.settingsComponent?.let {
            Intent(Intent.ACTION_MAIN).setComponent(it)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        private fun nativeStarter(): Any? = runCatching {
            if (Looper.myLooper() != Looper.getMainLooper()) return@runCatching null
            val methods = api.settingsMethods ?: return@runCatching null
            val starter = api.lazy(starterLazy ?: return@runCatching null) ?: return@runCatching null
            val implementation = methods.getValue(GmsSettingsTargets.select).invoke(starter)
            starter.takeIf { GmsSettingsPolicy.implemented(implementation?.javaClass?.name) }
        }.getOrNull()
        private fun canOpenSettings(): Boolean = runCatching {
            val context = api.context(host)
            val component = backend.settingsComponent ?: return@runCatching false
            val info = context.packageManager.getActivityInfo(component, 0)
            supported(context) && info.packageName == component.packageName && info.name == component.className &&
                info.enabled && info.exported && info.applicationInfo.enabled &&
                (info.permission.isNullOrEmpty() || context.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        }.getOrDefault(false)
        private fun openSettings(gen: Int, at: Long) {
            if (!valid(gen, at) || !canOpenSettings()) return
            val methods = api.settingsMethods ?: return
            if (methods.getValue(GmsSettingsTargets.falsing).invoke(falsing, 1) != false) return
            val starter = nativeStarter() ?: return
            if (!valid(gen, at) || !canOpenSettings()) return
            methods.getValue(GmsSettingsTargets.start).invoke(starter, settingsIntent(), true, null, false,
                UserHandle.getUserHandleForUid(0))
        }
        private fun read(context: Context): Boolean? = backend.read(context)
        private fun state(value: Boolean?): Any = api.state.newInstance().also { state ->
            fun put(name: String, v: Any?) = api.field(t.STATE, name).set(state, v)
            put("spec", backend.spec); put("label", backend.label); put("icon", icon)
            put("contentDescription", backend.label); put("expandedAccessibilityClassName", "android.widget.Switch")
            put("handlesLongClick", value != null && nativeStarter() != null && canOpenSettings())
            put("state", if (value == null) 0 else if (value) 2 else 1)
            put("stateDescription", if (value == null) "Unavailable" else if (value) "On" else "Off")
            api.field("${t.TILE}\$AdapterState", "value").setBoolean(state, value == true)
        }
        private fun publish(value: Boolean?) {
            if (destroyed) return
            val gated = value.takeIf { allowed() }
            last = gated; current = state(gated)
            callbacks.toList().forEach { callback ->
                runCatching { api.methods.getValue(t.callback).invoke(callback, api.methods.getValue(t.copy).invoke(current)) }
            }
        }
        private fun refresh(expected: Boolean? = null, gen: Int = generation.get(), at: Long = SystemClock.uptimeMillis()) {
            if (destroyed || !busy.compareAndSet(false, true)) return
            try {
                worker.execute {
                    val value = runCatching {
                        if (!allowed() || gen != generation.get()) return@runCatching null
                        val context = api.context(host)
                        if (expected != null) {
                            val complete = NativeToggleTransaction.toggle(expected, object : NativeToggleTransaction.Backend {
                                override fun allowed() = valid(gen, at)
                                override fun read() = read(context)
                                override fun write(enabled: Boolean) {
                                    check(valid(gen, at))
                                    backend.write(context, enabled)
                                }
                            })
                            if (!complete) ModuleLog.warn("native_toggle/${backend.spec} action not confirmed; no retry or external-state rollback")
                        }
                        if (allowed() && gen == generation.get()) read(context) else null
                    }.onFailure { ModuleLog.error("native_toggle/${backend.spec} provider unavailable", it) }.getOrNull()
                    main.post {
                        busy.set(false)
                        guarded { if (gen == generation.get()) publish(value) }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { busy.set(false) }
        }
        private val tick = object : Runnable {
            override fun run() {
                if (destroyed || !listening) return
                if (!enabled()) { publish(null); return }
                refresh(); main.postDelayed(this, 5000)
            }
        }
        private fun event(name: String, args: Array<out Any?>) {
            when (name) {
                "addCallback" -> args[0]?.let { callbacks.add(it); api.methods.getValue(t.callback).invoke(it, api.methods.getValue(t.copy).invoke(current)); refresh() }
                "removeCallback" -> callbacks.remove(args[0])
                "removeCallbacks" -> callbacks.clear()
                "removeCallbacksByType" -> callbacks.removeAll {
                    runCatching { api.methods.getValue(t.callbackType).invoke(it) == args[0] }.getOrDefault(false)
                }
                "setListening" -> {
                    if (args[1] == true) args[0]?.let { listeners.add(it) } else listeners.remove(args[0])
                    listening = listeners.isNotEmpty(); main.removeCallbacks(tick)
                    if (listening) main.post(tick) else { generation.incrementAndGet(); publish(null) }
                }
                "refreshState", "clearState" -> refresh()
            }
        }
    }
}
