/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.app.KeyguardManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import io.github.YGHFv.HyperExtend.core.TileMaterialSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap

internal class TileMaterialHooks private constructor(loader: ClassLoader, private val settings: HookSettings) {
    private val t = TileMaterialTargets
    private val methods = t.methods.associateWith { requireNotNull(NotificationHooks.resolve(loader, it)) }
    private val fields = t.fields.associate { (owner, name, expected) ->
        (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
            require(type.name == expected)
            require(Modifier.isStatic(modifiers) == (name == "INSTANCE" || name == "Companion"))
            isAccessible = true
        }
    }
    private val tokenClass = Class.forName(TOKEN, false, loader)
    private val constructor = tokenClass.getDeclaredConstructor()
    private val scope = TileMaterialScope<Render>()
    private val bindings = WeakHashMap<View, WeakReference<Binding>>()
    private val resources = WeakHashMap<Resources, ResourceColors>()
    private var restoring = false
    private var versions: Pair<Long, Long>? = null
    @Volatile private var ready = false

    private data class Render(val view: View, var written: Boolean = false)
    private data class Native(val method: SystemUiMethod, val tail: Array<Any?>)
    private data class ResourceColors(val configuration: Configuration,
        val off: Pair<IntArray, IntArray>, val on: Pair<IntArray, IntArray>)
    private fun field(owner: String, name: String) = fields.getValue(owner to name)
    private fun invoke(target: SystemUiMethod, receiver: Any? = null, vararg args: Any?) =
        methods.getValue(target).invoke(receiver, *args)
    private fun main() = Looper.myLooper() == Looper.getMainLooper()
    private fun classic() = invoke(t.classic) == true
    private fun material(view: View) = invoke(t.material, null, view.context) == true

    private fun owner(view: View): View? {
        if (view.javaClass.name == CARD) return view
        val icon = view.parent as? View ?: return null
        return icon.takeIf { it.javaClass.name == ICON && field(ICON, "icon").get(it) === view }
    }
    private fun state(owner: View): Any? = field(owner.javaClass.name, "state").get(owner)
    private fun eligible(view: View, owner: View): Boolean = runCatching {
        if (!ready || !main() || restoring) return@runCatching false
        val host = versions ?: view.context.packageManager.let {
            (it.getPackageInfo("com.android.systemui", 0).compatibleVersionCode to
                it.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode).also { pair -> versions = pair }
        }
        val state = state(owner) ?: return@runCatching false
        val companion = field(BASE, "Companion").get(null)
        val normal = if (owner.javaClass.name == CARD) true else {
            val item = owner.parent as? View
            val plainItem = item?.javaClass?.name == ITEM &&
                (field(ITEM, "mode").get(item) as? Enum<*>)?.name == "NORMAL"
            plainItem && !field(ICON, "card").getBoolean(owner) && !field(ICON, "isDetailTile").getBoolean(owner)
        }
        TileMaterialPolicy.eligible(settings.isOn(TileMaterialSettings.FEATURE), SafeModeRuntime.blocked,
            host.first, host.second, view.display?.displayId ?: view.context.hostDisplayIdOrNull(),
            classic(), material(view), invoke(t.theme, field(THEME, "INSTANCE").get(null)) == true, normal,
            field(STATE, "state").getInt(state), field(STATE, "activeBgColor").getInt(state),
            field(STATE, "disabledByPolicy").getBoolean(state) || invoke(t.restricted, companion, state) != false ||
                invoke(t.warning, companion, state) != false,
            field(STATE, "isTransient").getBoolean(state))
    }.getOrDefault(false)

    private fun copyToken(native: Any): Any {
        require(native.javaClass == tokenClass)
        return constructor.newInstance().also { copy ->
            for ((key, member) in fields) if (key.first == TOKEN) {
                member.set(copy, when (val value = member.get(native)) {
                    null -> null
                    is IntArray -> value.copyOf()
                    is FloatArray -> value.copyOf()
                    else -> error("Unexpected token payload")
                })
            }
        }
    }
    private fun replacement(view: View, native: Any): Any? {
        val tokens = field(TOKENS, "INSTANCE").get(null)
        val active = when {
            native === invoke(t.offToken, tokens) -> false
            native === invoke(t.onToken, tokens) -> true
            else -> return null
        }
        val cached = resources[view.resources]?.takeIf { it.configuration == view.resources.configuration } ?: run {
            // Do not create a plugin Context for every animation frame.
            val context = if (view.context.packageName == SystemUiPluginHooks.PACKAGE) view.context
                else view.context.createPackageContext(SystemUiPluginHooks.PACKAGE, 0)
                    .createConfigurationContext(view.resources.configuration)
            fun read(id: Int, name: String): Pair<IntArray, IntArray> {
                require(context.resources.getResourceName(id) == "${SystemUiPluginHooks.PACKAGE}:array/$name")
                return requireNotNull(TileMaterialPolicy.resource(context.resources.getIntArray(id)))
            }
            ResourceColors(Configuration(view.resources.configuration),
                read(OFF_RESOURCE, "cc_tile_default_blend_colors"),
                read(ON_RESOURCE, "miui_seekbar_fg_blend_colors_collapsed")).also { resources[view.resources] = it }
        }
        val values = if (active) cached.on else cached.off
        if (!TileMaterialPolicy.changes(field(TOKEN, "colors").get(native) as? IntArray,
                field(TOKEN, "blendModes").get(native) as? IntArray, values)) return null
        return copyToken(native).also {
            field(TOKEN, "colors").set(it, values.first.copyOf())
            field(TOKEN, "blendModes").set(it, values.second.copyOf())
        }
    }

    private inner class Binding(view: View, owner: View, var native: Native) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val viewRef = WeakReference(view)
        private val ownerRef = WeakReference(owner)
        private var stateRef = WeakReference(state(owner))
        private val configuration = Configuration(view.resources.configuration)
        private val keyguard = view.context.getSystemService(KeyguardManager::class.java)
        private val power = view.context.getSystemService(PowerManager::class.java)
        private var observer: ViewTreeObserver? = null
        private var stopped = false
        private var nextCheck = 0L
        private val timeout = Runnable { stop(true) }
        fun matches(owner: View) = ownerRef.get() === owner &&
            viewRef.get()?.resources?.configuration == configuration
        fun update(value: Native, owner: View) { native = value; stateRef = WeakReference(state(owner)) }
        fun start() {
            val view = viewRef.get() ?: return
            view.addOnAttachStateChangeListener(this)
            view.postDelayed(timeout, 30_000)
            if (view.isAttachedToWindow) onViewAttachedToWindow(view)
        }
        override fun onViewAttachedToWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }
        override fun onViewDetachedFromWindow(v: View) { stop(true) }
        override fun onPreDraw(): Boolean {
            if (!stopped) runCatching {
                val view = viewRef.get()
                val owner = ownerRef.get()
                if (view == null || owner == null || this@TileMaterialHooks.owner(view) !== owner || state(owner) !== stateRef.get() ||
                    view.resources.configuration != configuration) { stop(false); return@runCatching }
                if (SafeModeRuntime.blocked || !settings.isOn(TileMaterialSettings.FEATURE) ||
                    !view.isShown || view.windowVisibility != View.VISIBLE || !eligible(view, owner)) {
                    stop(true); return@runCatching
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= nextCheck) {
                    nextCheck = now + 500
                    if (keyguard?.isKeyguardLocked != false || power?.isInteractive != true) stop(true)
                }
            }.onFailure { stop(false) }
            return true
        }
        fun stop(restore: Boolean) {
            if (stopped) return
            stopped = true
            val view = viewRef.get()
            val owner = ownerRef.get()
            val owned = view != null && bindings[view]?.get() === this
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            if (view != null) {
                view.removeCallbacks(timeout); view.removeOnAttachStateChangeListener(this)
                if (owned) bindings.remove(view)
                runCatching {
                    if (restore && owner != null && state(owner) === stateRef.get() &&
                        view.resources.configuration == configuration &&
                        TileMaterialPolicy.restore(owned, SafeModeRuntime.blocked, classic(), material(view))) {
                        // Safe mode bypasses writer hooks, so it cannot prove ownership.
                        // Never replay a possibly stale snapshot after that bypass.
                        val previous = restoring
                        restoring = true
                        try { invoke(native.method, null, view, *native.tail) } finally { restoring = previous }
                    }
                }
            }
            viewRef.clear(); ownerRef.clear(); stateRef.clear()
        }
    }

    private fun install(): Int {
        if (!(methods.values + constructor).all { HookRuntime.deoptimize(it, "${TileMaterialSettings.FEATURE}/caller") }) return 0
        val writers = t.writers.map { target ->
            HookRuntime.hook(methods.getValue(target), "${TileMaterialSettings.FEATURE}/writer/${target.name}") { chain ->
                if (!ready || !main()) return@hook chain.proceed()
                val view = chain.args[0] as? View ?: return@hook chain.proceed()
                val active = scope.current?.takeIf { it.view === view }
                if (active == null) bindings[view]?.get()?.stop(false)
                val result = chain.proceed()
                if (target == t.write && result == true) active?.written = true
                result
            }
        }
        val renders = t.renders.map { target ->
            HookRuntime.hook(methods.getValue(target), "${TileMaterialSettings.FEATURE}/render/${target.parameters.size}") { chain ->
                if (!ready || !main() || restoring) return@hook chain.proceed()
                val view = chain.args[0] as? View ?: return@hook chain.proceed()
                val owner = runCatching { owner(view) }.getOrNull() ?: return@hook chain.proceed()
                if (!eligible(view, owner)) return@hook chain.proceed()
                if (runCatching {
                        view.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
                            view.context.getSystemService(PowerManager::class.java)?.isInteractive != true
                    }.getOrDefault(true)) return@hook chain.proceed()
                val args = chain.args.toTypedArray()
                val changed = runCatching {
                    if (args.size == 5 && ((args[3] as? Float)?.let { it.isFinite() && it in 0f..1f } != true))
                        return@runCatching false
                    var changed = false
                    for (index in if (args.size == 2) 1..1 else 1..2) {
                        replacement(view, args[index] ?: return@runCatching false)?.let { args[index] = it; changed = true }
                    }
                    changed
                }.getOrDefault(false)
                if (!changed) return@hook chain.proceed()
                val native = runCatching {
                    Native(target, chain.args.drop(1).map { if (it?.javaClass == tokenClass) copyToken(it) else it }.toTypedArray())
                }.getOrNull() ?: return@hook chain.proceed()
                val render = Render(view)
                try {
                    scope.within(render) { chain.proceed(args) }
                } finally {
                    if (render.written) runCatching {
                        var binding = bindings[view]?.get()
                        if (binding != null && !binding.matches(owner)) { binding.stop(false); binding = null }
                        if (binding != null) binding.update(native, owner) else {
                            val created = Binding(view, owner, native)
                            bindings[view] = WeakReference(created)
                            try { created.start() } catch (_: Throwable) { created.stop(true) }
                        }
                    }
                }
            }
        }
        ready = writers.all { it } && renders.all { it }
        return if (ready) writers.size + renders.size else 0
    }

    companion object {
        private const val ICON = TileCornerTargets.VIEW
        private const val CARD = CardTileColorTargets.VIEW
        private const val ITEM = "miui.systemui.controlcenter.qs.tileview.QSTileItemView"
        private const val BASE = "miui.systemui.controlcenter.qs.tileview.QSItemView"
        private const val STATE = TileCornerTargets.STATE
        private const val THEME = TileCornerTargets.THEME
        private const val TOKEN = "miuix.theme.token.ColorBlendToken"
        private const val TOKENS = "miui.systemui.util.MiuiColorBlendToken"
        private const val OFF_RESOURCE = 0x7f030008
        private const val ON_RESOURCE = 0x7f03003a
        fun install(loader: ClassLoader, settings: HookSettings): Int = TileMaterialHooks(loader, settings).install()
    }
}
