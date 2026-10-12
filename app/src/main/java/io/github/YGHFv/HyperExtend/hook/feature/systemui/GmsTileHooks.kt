/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
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

/** No native ScreenLockTile is instantiated or repurposed, even on failure. */
internal object GmsTileHooks {
    @Volatile var ready = false
        private set
    private val worker by lazy {
        ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
            { task -> Thread(task, "HyperExtend-GMS").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }
    }
    private val t = GmsTileTargets
    private const val PERMISSION = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
    private class SettingsApi(loader: ClassLoader) {
        val methods = GmsSettingsTargets.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val starter = GmsSettingsTargets.starter.let { (owner, name, type) ->
            StatusIconHost.field(loader, owner, name, type)
        }
    }
    private class Api(val loader: ClassLoader) {
        val settings = runCatching { SettingsApi(loader) }.getOrNull()
        val methods = t.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
        val fields = t.fields.associate { (owner, name, type) ->
            (owner to name) to StatusIconHost.field(loader, owner, name, type)
        }
        fun field(owner: String, name: String) = fields.getValue(owner to name)
        val tile = Class.forName(t.TILE, false, loader).also { type ->
            require(type.isInterface && FiveGTileContract.accepts(type.methods.map {
                "${it.name}(${it.parameterTypes.joinToString(",") { p -> p.name }}):${it.returnType.name}"
            }))
        }
        val state = Class.forName(t.BOOLEAN, false, loader).getConstructor()
        val icon = Class.forName("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon", false, loader)
            .getConstructor(Drawable::class.java)
        val sequence = Class.forName("com.android.internal.logging.InstanceIdSequence", false, loader)
            .getConstructor(Int::class.javaPrimitiveType)
        fun userContext(host: Any) = methods.getValue(t.userContext).invoke(host) as Context
        fun lazy(value: Any?) = methods.getValue(t.lazyGet).invoke(value)
    }
    fun install(loader: ClassLoader): Int {
        val api = Api(loader)
        if (!t.callers.all { HookRuntime.deoptimize(api.methods.getValue(it), "gms_tile/factory-caller") }) return 0
        ready = HookRuntime.hook(api.methods.getValue(t.create), "gms_tile/factory") { chain ->
            if (chain.args[0] != GmsTilePolicy.SPEC) return@hook chain.proceed()
            // A saved unknown module spec must not fall through to a native action.
            if (!ready || SafeModeRuntime.blocked || !HookRuntime.settings().isOn(GmsTilePolicy.FEATURE)) return@hook null
            runCatching {
                val factory = requireNotNull(chain.thisObject)
                val host = requireNotNull(api.lazy(api.field(t.FACTORY, "qsHostLazy").get(factory)))
                val context = api.userContext(host)
                if (!supported(context)) return@runCatching null
                try { context.packageManager.getPackageInfo(GmsTilePolicy.CORE, PackageManager.MATCH_DISABLED_COMPONENTS) }
                catch (_: PackageManager.NameNotFoundException) { return@runCatching null }
                val falsing = requireNotNull(api.lazy(api.field(t.FACTORY, "falsingManager").get(factory)))
                val starter = runCatching { api.settings?.starter?.get(factory) }.getOrNull()
                Tile(api, host, context, falsing, starter).proxy
            }.onFailure { ModuleLog.error("gms_tile: factory unavailable; no native fallback action", it) }.getOrNull()
        }
        return if (ready) 1 else 0
    }
    // 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
    @SuppressLint("NewApi")
    private fun supported(context: Context): Boolean {
        val user = context.getSystemService(UserManager::class.java) ?: return false
        return context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode == 202602260L &&
            user.isSystemUser && !user.isManagedProfile && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
    private class Tile(val api: Api, val host: Any, context: Context, val falsing: Any, val starterLazy: Any?) {
        private val main = Handler(Looper.getMainLooper())
        private val callbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val listeners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val generation = AtomicInteger()
        private val inFlight = AtomicBoolean()
        @Volatile private var destroyed = false
        @Volatile private var listening = false
        @Volatile private var currentUser = 0
        @Volatile private var last: Reading? = null
        private var pending: Click? = null
        private val instanceId = api.methods.getValue(t.nextId).invoke(api.sequence.newInstance(1 shl 20))
        private val icon = context.createPackageContext("io.github.YGHFv.HyperExtend", 0).let { module ->
            val id = module.resources.getIdentifier("ic_qs_gms", "drawable", module.packageName)
            require(id != 0)
            api.icon.newInstance(requireNotNull(module.getDrawable(id)).mutate())
        }
        @Volatile private var current = state(null)
        private data class Reading(val apps: List<GmsTilePolicy.App>, val usable: Boolean, val longClickable: Boolean)
        private data class Click(val apps: List<GmsTilePolicy.App>, val generation: Int, val at: Long,
            val action: GmsTilePolicy.Action)
        val proxy: Any = Proxy.newProxyInstance(api.loader, arrayOf(api.tile)) { self, method, args ->
            runCatching {
                when (method.name) {
                    "equals" -> self === args?.get(0)
                    "hashCode" -> System.identityHashCode(self)
                    "toString" -> "HyperExtendGmsTile"
                    "getState" -> api.methods.getValue(t.copy).invoke(current)
                    "getTileLabel" -> "Google services"
                    "getMetricsSpec", "getTileSpec" -> GmsTilePolicy.SPEC
                    "getInstanceId" -> instanceId
                    "getMetricsCategory" -> 0
                    "getCurrentTileUser" -> currentUser
                    "isAvailable" -> !destroyed && currentUser == 0 && !SafeModeRuntime.blocked &&
                        HookRuntime.settings().isOn(GmsTilePolicy.FEATURE)
                    "isDestroyed" -> destroyed
                    "isListening" -> listening
                    "isTileReady" -> true
                    "isConnected" -> false
                    "getDetailsViewModel" -> if (args?.size == 1) {
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as java.util.function.Consumer<Any?>).accept(null); false
                    } else null
                    "getDetailAdapter" -> null
                    "populate" -> args?.get(0)
                    "showDetail", "setDetailListening", "setTileSpec" -> null
                    "destroy" -> {
                        destroyed = true; generation.incrementAndGet()
                        main.post { listening = false; pending = null; callbacks.clear(); listeners.clear(); main.removeCallbacks(tick) }
                        null
                    }
                    "userSwitch" -> {
                        currentUser = args?.get(0) as? Int ?: -1; generation.incrementAndGet()
                        main.post { guarded { pending = null; publish(null); refresh() } }; null
                    }
                    "click", "secondaryClick", "longClick" -> {
                        val action = if (method.name == "longClick") GmsTilePolicy.Action.SETTINGS else GmsTilePolicy.Action.TOGGLE
                        last?.takeIf { it.usable && listening &&
                            (action == GmsTilePolicy.Action.TOGGLE || it.longClickable) }?.let {
                            val click = Click(it.apps, generation.get(), SystemClock.uptimeMillis(), action)
                            main.post { guarded { click(click) } }
                        }
                        null
                    }
                    else -> {
                        if (method.name == "setListening" && args?.get(1) == false) generation.incrementAndGet()
                        main.post { guarded { event(method.name, args.orEmpty()) } }; null
                    }
                }
            }.getOrElse { error ->
                ModuleLog.error("gms_tile: callback failed without a package action", error)
                when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null }
            }
        }
        private fun guarded(block: () -> Unit) {
            if (!destroyed) runCatching(block).onFailure { ModuleLog.error("gms_tile: event failed", it) }
        }
        private fun allowed(age: Long = 0): Boolean = runCatching {
            val active = api.userContext(host)
            val user = active.getSystemService(UserManager::class.java) ?: return@runCatching false
            GmsTilePolicy.canAct(HookRuntime.settings().isOn(GmsTilePolicy.FEATURE), SafeModeRuntime.blocked,
                currentUser == 0 && supported(active),
                active.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false,
                user.hasUserRestriction(UserManager.DISALLOW_APPS_CONTROL),
                active.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED, !destroyed && listening, age)
        }.getOrDefault(false)
        private fun app(context: Context, name: String): GmsTilePolicy.App? {
            return try {
                val info = context.packageManager.getPackageInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS)
                val application = requireNotNull(info.applicationInfo)
                GmsTilePolicy.App(name, application.uid, info.compatibleVersionCode,
                    context.packageManager.getApplicationEnabledSetting(name), application.enabled)
            } catch (_: PackageManager.NameNotFoundException) { null }
        }
        private fun read(): Reading? = runCatching {
            val active = api.userContext(host)
            if (!supported(active) || currentUser != 0) return@runCatching null
            val apps = GmsTilePolicy.packages.mapNotNull { app(active, it) }
            if (!GmsTilePolicy.usable(apps)) null else Reading(apps, allowed(), canOpenSettings(active))
        }.getOrNull()
        private fun settingsIntent() = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName(GmsSettingsTargets.PACKAGE, GmsSettingsTargets.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        private fun canOpenSettings(active: Context): Boolean = runCatching {
            if (api.settings == null || starterLazy == null) return@runCatching false
            val info = active.packageManager.getActivityInfo(settingsIntent().component!!, 0)
            GmsSettingsPolicy.target(info.packageName, info.name,
                info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                info.applicationInfo.enabled, info.enabled, info.exported,
                info.permission.isNullOrEmpty() || active.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        }.getOrDefault(false)
        private fun nativeStarter(): Any? = runCatching {
            if (Looper.myLooper() != Looper.getMainLooper()) return@runCatching null
            val settings = api.settings ?: return@runCatching null
            val lazy = starterLazy ?: return@runCatching null
            val starter = api.lazy(lazy) ?: return@runCatching null
            val internal = settings.methods.getValue(GmsSettingsTargets.select).invoke(starter)
            starter.takeIf { GmsSettingsPolicy.implemented(internal?.javaClass?.name) }
        }.getOrNull()
        private fun openSettings(click: Click) {
            if (click.action != GmsTilePolicy.Action.SETTINGS || !valid(click) || last?.longClickable != true) return
            val active = api.userContext(host)
            if (!canOpenSettings(active)) return
            val starter = nativeStarter() ?: return
            if (!valid(click) || !canOpenSettings(api.userContext(host))) return
            api.settings?.methods?.getValue(GmsSettingsTargets.start)?.invoke(starter,
                settingsIntent(), true, null, false, UserHandle.getUserHandleForUid(0))
        }
        private fun valid(click: Click) = click.generation == generation.get() &&
            allowed(SystemClock.uptimeMillis() - click.at)
        private fun click(click: Click) {
            val falseTouch = if (click.action == GmsTilePolicy.Action.SETTINGS)
                api.settings?.methods?.getValue(GmsSettingsTargets.falsing) else api.methods.getValue(t.falseTap)
            if (!valid(click) || pending != null || falseTouch?.invoke(falsing, 1) != false) return
            if (inFlight.get()) pending = click else refresh(click)
        }
        private fun state(reading: Reading?): Any = api.state.newInstance().also { value ->
            fun put(name: String, v: Any?) = api.field(t.STATE, name).set(value, v)
            val on = reading?.apps?.singleOrNull { it.name == GmsTilePolicy.CORE }?.enabled == true
            val mixed = reading?.apps?.any { it.enabled != on } == true
            put("spec", GmsTilePolicy.SPEC); put("label", "Google services"); put("icon", icon)
            put("contentDescription", "Google services"); put("expandedAccessibilityClassName", "android.widget.Switch")
            put("handlesLongClick", reading?.usable == true && reading.longClickable)
            put("state", if (reading?.usable != true) 0 else if (on) 2 else 1)
            put("stateDescription", when { reading?.usable != true -> "Unavailable"; mixed -> "Mixed package states"; on -> "On"; else -> "Off" })
            api.field("${t.TILE}\$AdapterState", "value").setBoolean(value, on)
        }
        private fun publish(reading: Reading?) {
            if (destroyed) return
            val usable = reading?.usable == true && allowed()
            val gated = reading?.copy(usable = usable,
                longClickable = usable && reading.longClickable && nativeStarter() != null)
            last = gated; current = state(gated)
            callbacks.toList().forEach { callback ->
                runCatching { api.methods.getValue(t.callback).invoke(callback, api.methods.getValue(t.copy).invoke(current)) }
            }
        }
        private fun refresh(click: Click? = null) {
            if (destroyed || !inFlight.compareAndSet(false, true)) return
            val expected = generation.get()
            try {
                worker.execute {
                    val result = runCatching {
                        val first = read()
                        if (click != null && first != null && GmsTilePolicy.shouldWrite(click.action,
                                valid(click), first.usable, GmsTilePolicy.same(click.apps, first.apps))) {
                            val active = api.userContext(host)
                            val started = SystemClock.uptimeMillis()
                            fun transactionAllowed() = click.generation == generation.get() && allowed() &&
                                GmsTilePolicy.transactionAge(SystemClock.uptimeMillis() - started)
                            val outcome = GmsPackageTransaction.apply(first.apps, object : GmsPackageTransaction.Backend {
                                override fun allowed() = transactionAllowed()
                                override fun read(name: String) = app(active, name)
                                override fun write(name: String, state: Int) {
                                    check(transactionAllowed())
                                    active.packageManager.setApplicationEnabledSetting(name, state, 0)
                                }
                            })
                            if (!outcome.complete) ModuleLog.warn("gms_tile: action incomplete; restored=${outcome.restored}, touched=${outcome.changed}")
                        }
                        if (click != null) read() else first
                    }.onFailure { ModuleLog.error("gms_tile: read/action failed; not retried", it) }.getOrNull()
                    main.post {
                        inFlight.set(false)
                        guarded {
                            if (generation.get() == expected) publish(result)
                            if (click?.action == GmsTilePolicy.Action.SETTINGS && valid(click) && result?.longClickable == true)
                                runCatching { openSettings(click) }.onFailure {
                                    ModuleLog.warn("gms_tile: settings route unavailable; no package action")
                                }
                            val next = pending; pending = null
                            if (next != null && valid(next)) refresh(next)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { inFlight.set(false) }
        }
        private val tick = object : Runnable {
            override fun run() {
                if (destroyed || !listening) return
                if (SafeModeRuntime.blocked || !HookRuntime.settings().isOn(GmsTilePolicy.FEATURE)) { publish(null); return }
                refresh()
                main.postDelayed(this, 5000L)
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
                    if (listening) main.post(tick) else { generation.incrementAndGet(); pending = null }
                }
                "refreshState", "clearState" -> refresh()
            }
        }
    }
}
