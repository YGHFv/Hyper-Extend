/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.os.UserHandle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A separate QSTile implementation. Never repurpose NFC or let failures fall through to NFC. */
internal object FiveGTileHooks {
    @Volatile var ready = false
        private set
    private const val FACTORY = StockTilesTargets.FACTORY
    private const val TILE = "com.android.systemui.plugins.qs.QSTile"
    private const val STATE = "$TILE\$State"
    private const val HOST = "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter"
    private val create = SystemUiMethod(FACTORY, "createTile", TILE, listOf("java.lang.String"))
    private val callers = listOf(
        SystemUiMethod(FACTORY, "createTile", TILE, listOf("java.lang.String", "boolean")),
        SystemUiMethod("com.android.systemui.plugins.qs.QSFactory", "createTile", TILE, listOf("java.lang.String", "boolean")),
        SystemUiMethod("com.android.systemui.qs.panels.domain.interactor.TilesAvailabilityInteractor", "getUnavailableTiles",
            "java.lang.Object", listOf("java.lang.Iterable", "kotlin.coroutines.jvm.internal.ContinuationImpl")),
        SystemUiMethod("com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractorImpl", "createTileSync",
            TILE, listOf("com.android.systemui.qs.pipeline.shared.TileSpec")),
    )
    fun preparePlugin(loader: ClassLoader): Boolean = listOf(
        SystemUiMethod("miui.systemui.controlcenter.qs.QSController", "createTile", TILE, listOf("java.lang.String")),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelTilesDelegate", "getQSTile", TILE, listOf("java.lang.String")),
    ).map { spec -> NotificationHooks.resolve(loader, spec)?.let { HookRuntime.deoptimize(it, "stock_tiles/5g_plugin_caller") } == true }.all { it }
    private val main = Handler(Looper.getMainLooper())
    // One bounded worker for all tile instances; no queued retries of a network write.
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(16),
        { task -> Thread(task, "HyperExtend-5G").apply { isDaemon = true } }).apply { allowCoreThreadTimeOut(true) }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val api = Api(loader)
        val warned = AtomicBoolean(false)
        val stages = ConcurrentHashMap.newKeySet<String>()
        fun report(stage: String) {
            if (stages.add(stage)) ModuleLog.info("stock_tiles/5g: $stage")
        }
        fun reject(reason: String): Any? { report("unavailable=$reason"); return null }
        val methods = (listOf(create) + callers).map { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!methods.map { HookRuntime.deoptimize(it, "stock_tiles/5g_factory") }.all { it }) return 0
        ready = HookRuntime.hook(methods.first(), "stock_tiles/5g_factory") { chain ->
            if (chain.args[0] != FiveGTilePolicy.SPEC) return@hook chain.proceed()
            report("factory reached")
            // A saved module tile must never be interpreted as a different native tile.
            if (!ready || !settings.isOn(StockTilesPolicy.FEATURE)) return@hook reject("disabled")
            runCatching {
                val factory = chain.thisObject ?: return@runCatching reject("missing_factory")
                val host = api.lazyGet.invoke(api.host.get(factory)) ?: return@runCatching reject("missing_host")
                val context = api.userContext.invoke(host) as Context
                // UserTracker uses createContextAsUser(..., 0): its resource-only LoadedApk
                // can have no Application even after the process Application is initialized.
                if (!api.applicationReady()) return@runCatching reject("application_not_ready")
                if (context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode != 202602260L)
                    return@runCatching reject("host_version")
                if (context.getSystemService(UserManager::class.java)?.isSystemUser != true) return@runCatching reject("not_owner")
                val binder = api.binder() ?: return@runCatching reject("telephony_unavailable")
                if (api.capable.invoke(binder) != true) return@runCatching reject("not_5g_capable")
                val falsing = api.lazyGet.invoke(api.falsing.get(factory)) ?: return@runCatching reject("missing_falsing")
                val starter = runCatching { api.starter?.get(factory) }.getOrNull()
                Tile(api, host, context, falsing, starter, settings).proxy.also { report("tile created") }
            }.onFailure {
                if (warned.compareAndSet(false, true)) ModuleLog.error("stock_tiles: cannot create independent 5G tile", it)
            }.getOrNull()
        }
        return if (ready) 1 else 0
    }

    private class Api(val loader: ClassLoader) {
        fun type(name: String) = Class.forName(name, false, loader)
        fun method(owner: String, name: String, result: String, vararg args: String, static: Boolean = false): Method =
            checkNotNull(NotificationHooks.resolve(loader, SystemUiMethod(owner, name, result, args.toList(), static)))
        fun field(owner: String, name: String, expected: String) = type(owner).getDeclaredField(name).apply {
            require(this.type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val tile = type(TILE)
        init {
            require(tile.isInterface && FiveGTileContract.accepts(tile.methods.map {
                "${it.name}(${it.parameterTypes.joinToString(",") { type -> type.name }}):${it.returnType.name}"
            }))
        }
        val state = type("$TILE\$BooleanState").getConstructor()
        val copy = method("$TILE\$BooleanState", "copy", STATE)
        val stateFields = listOf("state" to "int", "spec" to "java.lang.String", "label" to "java.lang.CharSequence",
            "contentDescription" to "java.lang.CharSequence", "stateDescription" to "java.lang.CharSequence",
            "expandedAccessibilityClassName" to "java.lang.String", "handlesLongClick" to "boolean",
            "icon" to "$TILE\$Icon").associate { (name, type) -> name to field(STATE, name, type) }
        val value = field("$TILE\$AdapterState", "value", "boolean")
        val drawableIcon = type("com.android.systemui.qs.tileimpl.QSTileImpl\$DrawableIcon").getConstructor(Drawable::class.java)
        val callback = method("$TILE\$Callback", "onStateChanged", "void", STATE)
        val callbackType = method("$TILE\$Callback", "getCallbackType", "int")
        val host = field(FACTORY, "qsHostLazy", "dagger.Lazy")
        val falsing = field(FACTORY, "falsingManager", "dagger.Lazy")
        // The optional settings route must not veto an otherwise working 5G tile.
        val starter = runCatching { field(FACTORY, "activityStarter", "dagger.Lazy") }.getOrNull()
        val startActivity = NotificationHooks.resolve(loader, FiveGSettingsTargets.start)
        val falseLongTap = NotificationHooks.resolve(loader, FiveGSettingsTargets.falsing)
        val lazyGet = method("dagger.Lazy", "get", "java.lang.Object")
        val userContext = method(HOST, "getUserContext", "android.content.Context")
        val falseTap = method("com.android.systemui.plugins.FalsingManager", "isFalseTap", "boolean", "int")
        // onPackageReady runs before Application exists. Resolve only: invoking getDefault
        // there poisons MIUI's Holder class for the entire process, even when caught.
        private val managerGet = method("miui.telephony.TelephonyManagerEx", "getDefault", "miui.telephony.TelephonyManagerEx", static = true)
        private val currentApplication = method("android.app.ActivityThread", "currentApplication", "android.app.Application", static = true)
        val binderGet = method("miui.telephony.TelephonyManagerEx", "getMiuiTelephony", "miui.telephony.IMiuiTelephony")
        val capable = method("miui.telephony.IMiuiTelephony", "isFiveGCapable", "boolean")
        val enabled = method("miui.telephony.IMiuiTelephony", "isUserFiveGEnabled", "boolean", "int")
        val setEnabled = method("miui.telephony.IMiuiTelephony", "setUserFiveGEnabled", "void", "boolean", "int")
        private val sequence = type("com.android.internal.logging.InstanceIdSequence").getConstructor(Int::class.javaPrimitiveType)
            .newInstance(1 shl 20)
        private val nextId = method("com.android.internal.logging.InstanceIdSequence", "newInstanceId", "com.android.internal.logging.InstanceId")
        fun instanceId(): Any = checkNotNull(nextId.invoke(sequence))
        fun applicationReady(): Boolean =
            (currentApplication.invoke(null) as? Context)?.packageName == "com.android.systemui"
        fun binder(): Any? {
            if (!applicationReady()) return null
            return binderGet.invoke(managerGet.invoke(null))
        }
    }

    private class Tile(val api: Api, val host: Any, val context: Context, val falsing: Any,
        val starter: Any?, val settings: HookSettings) {
        private val callbacks = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val listeners = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        private val inFlight = AtomicBoolean(false)
        @Volatile private var destroyed = false
        @Volatile private var listening = false
        private val generation = AtomicInteger(0)
        @Volatile private var currentUser = 0
        @Volatile private var lastReading: Reading? = null
        private var pendingClick: Click? = null
        private var writing = false
        private val instanceId = api.instanceId()
        // 图标按「5G 开关样式」选择。上游的 on/off 其实是「同图标两种样式」而不是开/关两套图，
        // 所以这里只随样式变，不随开关状态变。
        private val icon = context.createPackageContext("io.github.YGHFv.HyperExtend", 0).let { module ->
            val asset = FiveGTilePolicy.iconAsset(settings.string(FiveGTilePolicy.STYLE))
            val id = module.resources.getIdentifier(asset, "drawable", module.packageName)
            require(id != 0)
            val drawable = checkNotNull(module.getDrawable(id)).mutate()
            require(drawable.constantState != null)
            api.drawableIcon.newInstance(drawable)
        }
        @Volatile private var current = makeState(null)
        val proxy: Any = Proxy.newProxyInstance(api.loader, arrayOf(api.tile)) { proxy, method, args ->
            runCatching {
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "HyperExtend5GTile"
                    "getState" -> api.copy.invoke(current)
                    "getTileLabel" -> FiveGTilePolicy.LABEL
                    "getMetricsSpec" -> FiveGTilePolicy.SPEC
                    "getTileSpec" -> FiveGTilePolicy.SPEC
                    "getInstanceId" -> instanceId
                    "getMetricsCategory" -> 0
                    "getCurrentTileUser" -> currentUser
                    "isAvailable" -> !destroyed && currentUser == 0 && settings.isOn(StockTilesPolicy.FEATURE) && !SafeModeRuntime.blocked
                    "isDestroyed" -> destroyed
                    "isListening" -> listening
                    "isTileReady" -> true
                    "isConnected" -> false
                    "getDetailsViewModel" -> if (args?.size == 1) {
                        @Suppress("UNCHECKED_CAST")
                        (args[0] as java.util.function.Consumer<Any?>).accept(null); false
                    } else null
                    "populate" -> args?.get(0)
                    "getDetailAdapter" -> null
                    "userSwitch" -> {
                        currentUser = args?.get(0) as? Int ?: -1
                        generation.incrementAndGet()
                        main.post { runCatching { if (!destroyed) { pendingClick = null; publish(null); refresh() } } }
                        null
                    }
                    "setListening" -> {
                        if (args?.get(1) == false) generation.incrementAndGet()
                        main.post { runCatching { event(method.name, args.orEmpty()) } }
                        null
                    }
                    "click", "secondaryClick", "longClick" -> {
                        val reading = lastReading
                        val openSettings = method.name == "longClick"
                        if (listening && reading?.usable == true && (!openSettings || reading.longClickable)) {
                            val intent = Click(reading.selection, generation.get(), SystemClock.uptimeMillis(), openSettings)
                            main.post { runCatching { click(intent) } }
                        }
                        null
                    }
                    "destroy" -> {
                        destroyed = true
                        generation.incrementAndGet()
                        main.post { listening = false; pendingClick = null; main.removeCallbacks(tick); callbacks.clear(); listeners.clear() }
                        null
                    }
                    else -> {
                        main.post { runCatching { event(method.name, args.orEmpty()) } }
                        null
                    }
                }
            }.getOrElse {
                when (method.returnType) { Boolean::class.javaPrimitiveType -> false; Int::class.javaPrimitiveType -> 0; else -> null }
            }
        }
        private data class Reading(val selection: FiveGTilePolicy.Selection, val usable: Boolean, val longClickable: Boolean)
        private data class Click(val selection: FiveGTilePolicy.Selection, val generation: Int, val time: Long,
            val openSettings: Boolean = false)
        private fun settingsIntent() = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName(FiveGSettingsTargets.PACKAGE, FiveGSettingsTargets.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        private fun canOpenSettings(active: Context): Boolean = runCatching {
            if (starter == null || api.startActivity == null || api.falseLongTap == null) return@runCatching false
            val info = active.packageManager.getActivityInfo(settingsIntent().component!!, 0)
            FiveGTilePolicy.settingsTarget(info.packageName, info.name,
                info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                info.applicationInfo.enabled, info.enabled, info.exported,
                info.permission.isNullOrEmpty() || active.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
        }.getOrDefault(false)
        private fun read(): Reading? = runCatching {
            // Profile identity checks require API 30; older hosts cannot operate this tile.
            if (android.os.Build.VERSION.SDK_INT < 30) return@runCatching null
            val active = api.userContext.invoke(host) as Context
            val user = active.getSystemService(UserManager::class.java) ?: return@runCatching null
            val locked = active.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
            val sub = SubscriptionManager.getDefaultDataSubscriptionId()
            val slot = SubscriptionManager.getSlotIndex(sub)
            val simReady = slot in 0..1 && active.getSystemService(TelephonyManager::class.java)?.getSimState(slot) == TelephonyManager.SIM_STATE_READY
            val usable = FiveGTilePolicy.canToggle(settings.isOn(StockTilesPolicy.FEATURE), SafeModeRuntime.blocked,
                currentUser == 0 && user.isSystemUser && !user.isManagedProfile, locked, user.hasUserRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS),
                Settings.Global.getInt(active.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0, slot, simReady)
            val binder = api.binder() ?: return@runCatching null
            if (api.capable.invoke(binder) != true || slot !in 0..1) return@runCatching null
            Reading(FiveGTilePolicy.Selection(sub, slot, api.enabled.invoke(binder, slot) as Boolean), usable,
                usable && canOpenSettings(active))
        }.getOrNull()

        private fun makeState(reading: Reading?): Any = api.state.newInstance().also { state ->
            fun put(name: String, value: Any?) { api.stateFields.getValue(name).set(state, value) }
            put("spec", FiveGTilePolicy.SPEC); put("label", FiveGTilePolicy.LABEL)
            put("contentDescription", FiveGTilePolicy.LABEL); put("icon", icon)
            put("expandedAccessibilityClassName", "android.widget.Switch")
            put("handlesLongClick", reading?.usable == true && reading.longClickable)
            put("state", if (reading?.usable != true) 0 else if (reading.selection.on) 2 else 1)
            put("stateDescription", if (reading?.usable != true) "不可用" else if (reading.selection.on) "已开启" else "已关闭")
            api.value.setBoolean(state, reading?.selection?.on == true)
        }
        private fun publish(reading: Reading?) {
            current = makeState(reading)
            lastReading = reading
            callbacks.toList().forEach { callback -> runCatching { api.callback.invoke(callback, api.copy.invoke(current)) } }
        }
        private val tick = object : Runnable {
            override fun run() {
                if (destroyed || !listening) return
                runCatching {
                    if (SafeModeRuntime.blocked || !settings.isOn(StockTilesPolicy.FEATURE)) publish(null) else refresh()
                }
                main.postDelayed(this, 1500)
            }
        }
        private fun valid(click: Click, reading: Reading?): Boolean =
            !destroyed && listening && generation.get() == click.generation && reading?.usable == true &&
                !SafeModeRuntime.blocked && settings.isOn(StockTilesPolicy.FEATURE) &&
                FiveGTilePolicy.sameSelection(click.selection, reading.selection, SystemClock.uptimeMillis() - click.time)

        private fun click(click: Click) {
            val falseTouch = (if (click.openSettings) api.falseLongTap else api.falseTap) ?: return
            if (!valid(click, lastReading) || writing || pendingClick != null || falseTouch.invoke(falsing, 1) != false) return
            // A periodic read must not swallow a real tap. Keep at most one short-lived intent.
            if (inFlight.get()) pendingClick = click else refresh(click)
        }
        private fun refresh(click: Click? = null) {
            if (destroyed || !inFlight.compareAndSet(false, true)) return
            val expected = generation.get()
            writing = click != null
            try {
                worker.execute {
                    val first = read()
                    if (click != null && !click.openSettings && valid(click, first)) {
                        // Recheck the original subscription, slot and value before one write. No retries.
                        val checked = read()
                        if (valid(click, checked)) {
                            runCatching { api.binder()?.let { binder ->
                                if (valid(click, checked)) api.setEnabled.invoke(binder, !click.selection.on, click.selection.slot)
                            } }
                        }
                    }
                    val result = if (click != null) read() else first
                    main.post {
                        inFlight.set(false)
                        writing = false
                        runCatching {
                            if (!destroyed && generation.get() == expected) publish(result)
                            if (click?.openSettings == true && valid(click, result) && result?.longClickable == true)
                                runCatching { openSettings(click) }
                            val pending = pendingClick
                            pendingClick = null
                            if (pending != null && valid(pending, lastReading)) refresh(pending)
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { inFlight.set(false); writing = false }
        }
        // 只运行在 HyperOS 4（API 36）宿主进程，宿主侧 API 门禁不适用模块的 minSdk。
        @SuppressLint("NewApi")
        private fun openSettings(click: Click) {
            // No network write on this path. Native ActivityStarter owns panel dismissal
            // and launch restrictions, and receives an explicit owner UserHandle.
            if (!valid(click, lastReading)) return
            val active = api.userContext.invoke(host) as? Context ?: return
            val user = active.getSystemService(UserManager::class.java) ?: return
            if (!user.isSystemUser || user.isManagedProfile || active.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
                SubscriptionManager.getDefaultDataSubscriptionId() != click.selection.subscription ||
                SubscriptionManager.getSlotIndex(click.selection.subscription) != click.selection.slot ||
                !canOpenSettings(active) || !valid(click, lastReading)) return
            val activityStarter = api.lazyGet.invoke(starter) ?: return
            api.startActivity?.invoke(activityStarter, settingsIntent(), true, null, false, UserHandle.getUserHandleForUid(0))
        }
        private fun event(name: String, args: Array<out Any?>) {
            if (destroyed) return
            when (name) {
                "addCallback" -> args[0]?.let { callbacks.add(it); api.callback.invoke(it, api.copy.invoke(current)); refresh() }
                "removeCallback" -> callbacks.remove(args[0])
                "removeCallbacks" -> callbacks.clear()
                "removeCallbacksByType" -> callbacks.removeAll { runCatching { api.callbackType.invoke(it) == args[0] }.getOrDefault(false) }
                "setListening" -> {
                    if (args[1] == true) args[0]?.let { listeners.add(it) } else listeners.remove(args[0])
                    listening = listeners.isNotEmpty(); main.removeCallbacks(tick)
                    if (listening) main.post(tick) else { generation.incrementAndGet(); pendingClick = null }
                }
                "refreshState", "clearState" -> refresh()
            }
        }
    }
}
