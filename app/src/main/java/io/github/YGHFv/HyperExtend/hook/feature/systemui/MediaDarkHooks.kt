/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later
 * AlwaysDark adapted to OS4's native material effects without removing AOD listeners.
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.TargetApi
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.core.MediaBackgroundSettings as Artwork
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap

@TargetApi(28) // Only the audited OS4 host is admitted.
internal class MediaDarkHooks private constructor(loader: ClassLoader, settings: HookSettings) {
    private val alwaysDark = settings.isOn(FEATURE)
    private val artworkMode = if (settings.isOn(Artwork.FEATURE)) Artwork.mode(settings.string(Artwork.MODE)) else 0
    private val artworkBlur = settings.number(Artwork.BLUR, 10, 0, 20)
    private val artworkTransition = settings.isOn(Artwork.TRANSITION)
    private val controllerType = type(loader, SystemUiTargets.MEDIA_CONTROLLER)
    private val holderType = type(loader, SystemUiTargets.MEDIA_HOLDER)
    private val headerType = type(loader, MediaDarkTargets.HEADER)
    private val context = controllerType.getDeclaredField("context").apply {
        require(type == Context::class.java); isAccessible = true
    }
    private val holder = controllerType.getDeclaredField("holder").apply {
        require(type == holderType); isAccessible = true
    }
    private val player = holderType.getDeclaredField("player").apply { isAccessible = true }
    private val background = holderType.getDeclaredField("mediaBg").apply { isAccessible = true }
    private val getHolder = headerType.getDeclaredMethod("getMediaViewHolder").apply { isAccessible = true }
    private val foreground = method(loader, MediaDarkTargets.foreground)
    private val tiny = method(loader, SystemUiTargets.flipTinyScreen)
    private val aodType = type(loader, "com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController")
    private val aod = controllerType.getDeclaredField("fullAodController").apply { isAccessible = true }
    private val lazyGet = type(loader, "dagger.Lazy").getDeclaredMethod("get")
    private val pendingAod = aodType.getDeclaredField("mWillDoAodAnim").apply { isAccessible = true }
    private val artwork = controllerType.getDeclaredField("artWorkDrawable").apply { isAccessible = true }
    private val mediaData = controllerType.getDeclaredField("mediaData").apply { isAccessible = true }
    private val hasArtwork = mediaData.type.getDeclaredField("artwork").apply { isAccessible = true }
    private val artworkChanged = controllerType.getDeclaredField("isArtWorkUpdate").apply { isAccessible = true }
    private val material = controllerType.getDeclaredField("notificationMaterialStateInteractor").apply { isAccessible = true }
    private val keyguard = material.type.getDeclaredField("isOnKeyguard").apply { isAccessible = true }
    private val showingKeyguard = material.type.getDeclaredField("isShowingKeyguardNotification").apply { isAccessible = true }
    private val fullAod = material.type.getDeclaredField("enterFullAod").apply { isAccessible = true }
    private val flowValue = type(loader, "kotlinx.coroutines.flow.StateFlow").getDeclaredMethod("getValue")
    private val clearEffects = if (artworkMode == 0) emptyMap() else MediaDarkTargets.effects.associate { spec ->
        spec.owner to method(loader, spec.copy(name = "clear", parameters = listOf("java.lang.Object")))
    }
    private val materialType = if (artworkMode == 0) null else method(loader, SystemUiMethod("com.miui.systemui.util.MiGlassCompat",
        "setMiViewMaterialTypeCompat", "void", listOf("int", "android.view.View"), isStatic = true))
    private val states = WeakHashMap<View, WeakReference<Binding>>()
    private val nested = ThreadLocal<Boolean>()
    private var hostVersion: Long? = null
    private var ready = false

    private data class Effect(val method: Method, val receiver: Any, val context: Context)

    private inner class Binding(val header: View) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        val effect = MediaEffectOwnership<Effect>()
        var controller = WeakReference<Any>(null)
        var colorsChanged = false
        private var observer: ViewTreeObserver? = null
        private var configuration: Configuration? = null
        private var isTiny = false
        private var restoreFailed = false
        private val job = MediaArtworkJob()
        private val request = MediaArtworkRequestState()
        var mediaBound = true
        private var processed: MediaArtworkDrawable? = null
        private var custom: Drawable? = null
        private var nativeBackground: Drawable? = null
        private var nativeEffect: Effect? = null
        private val retryMaterial = MediaEffectOwnership<Effect>()
        private var effectReplacedCustom = false
        private var boundHolder = WeakReference<Any>(null)
        private var boundBackground = WeakReference<View>(null)
        val hasCustom get() = custom != null

        fun beforeHolderChange(next: Any?) {
            if (next !== boundHolder.get()) detachArtwork()
        }

        fun syncHolder() {
            val current = getHolder.invoke(header)
            if (current === boundHolder.get()) return
            job.cancel()
            processed?.finishTransition()
            boundBackground.get()?.let { if (custom != null && it.background === custom) it.background = nativeBackground }
            request.invalidate(); processed = null; custom = null; nativeBackground = null; nativeEffect = null
            retryMaterial.clear(); effect.clear(); colorsChanged = false
            effectReplacedCustom = false
            boundHolder = WeakReference(current)
            boundBackground = WeakReference<View>(current?.let { background.get(it) as? View })
        }

        fun wantsArtwork(): Boolean {
            val owner = controller.get() ?: return false
            if (!mediaBound || !ownsHolder(owner) || artworkMode == 0) return false
            val interactor = material.get(owner) ?: return false
            return MediaBackgroundPolicy.eligible(artworkMode, header.isAttachedToWindow, tiny.invoke(null, header.context) != false, SafeModeRuntime.blocked,
                flowValue.invoke(keyguard.get(interactor)) as? Boolean,
                flowValue.invoke(showingKeyguard.get(interactor)) as? Boolean,
                flowValue.invoke(fullAod.get(interactor)) as? Boolean,
                pendingAod.getBoolean(lazyGet.invoke(aod.get(owner))))
        }

        fun updateArtwork(force: Boolean = false) {
            if (artworkMode == 0) return
            val owner = controller.get()
            val bound = owner != null && mediaBound && ownsHolder(owner)
            val data = if (bound) mediaData.get(owner) else null
            val next = if (data != null && hasArtwork.get(data) != null) artwork.get(owner) as? Drawable else null
            when (request.next(next, bound, wantsArtwork(), force)) {
                MediaArtworkRequestState.Action.KEEP -> return
                MediaArtworkRequestState.Action.CLEAR -> { discardArtwork(); return }
                MediaArtworkRequestState.Action.PROCESS -> Unit
            }
            job.cancel()
            // Keep the last owned frame while processing; missing/failed art still restores native.
            val pixels = MediaArtworkJob.snapshot(next!!) ?: run { discardArtwork(); return }
            val weak = WeakReference(this)
            job.submit(pixels, artworkMode, artworkBlur) { result ->
                weak.get()?.publish(result)
            }
        }

        private fun publish(pixels: IntArray?) {
            try {
                if (getHolder.invoke(header) !== boundHolder.get()) { syncHolder(); return }
                if (!wantsArtwork()) { request.invalidate(); discardArtwork(); return }
                if (pixels == null) { discardArtwork(); return }
                val id = header.resources.getIdentifier("notification_item_bg_radius", "dimen", "com.android.systemui")
                if (id == 0) { discardArtwork(); return }
                val current = processed
                if (current != null && custom === current && backgroundView()?.background === current) {
                    current.update(pixels, artworkTransition)
                } else {
                    processed = MediaArtworkDrawable(pixels, artworkMode, header.resources.getDimension(id))
                }
                updateVisual()
            } catch (failure: Throwable) { fail(failure) }
        }

        fun beforeEffect() {
            val view = backgroundView()
            effectReplacedCustom = custom != null
            if (custom != null && view?.background === custom) view!!.background = nativeBackground
            custom = null
            retryMaterial.clear()
        }

        fun afterEffect(value: Effect) {
            nativeEffect = value
            nativeBackground = backgroundView()?.background
            retryMaterial.clear()
            updateVisual()
            // A keyguard/material event can restore the native background before
            // pre-draw sees the old image; restore its foreground in the same event.
            if (effectReplacedCustom && custom == null) controller.get()?.let { foreground.invoke(it) }
            effectReplacedCustom = false
        }

        private fun updateVisual() {
            if (artworkMode == 0) return
            if (!wantsArtwork() || processed == null) { restoreArtwork(); return }
            val view = backgroundView() ?: return
            if (view.background === processed && custom === processed) return
            if (custom != null && view.background !== custom) {
                // A writer outside the audited effect chain wins until the next bind/effect.
                processed?.finishTransition()
                custom = null; processed = null; retryMaterial.clear(); job.cancel()
                controller.get()?.let { foreground.invoke(it) }
                return
            }
            val native = nativeEffect ?: return
            nativeBackground = view.background
            try {
                unmodified {
                    clearEffects.getValue(native.method.declaringClass.name).invoke(native.receiver, header)
                    materialType!!.invoke(null, 0, view)
                    view.background = processed
                }
                custom = processed
            } catch (failure: Throwable) {
                custom = null; processed = null
                unmodified {
                    view.background = nativeBackground
                    val restoreContext = if (alwaysDark) dark(native.context) else native.context
                    native.method.invoke(native.receiver, header, restoreContext)
                }
                throw failure
            }
            controller.get()?.let { foreground.invoke(it) }
        }

        private fun restoreArtwork() {
            val view = backgroundView()
            val owned = custom != null && view?.background === custom
            if (owned) view!!.background = nativeBackground
            processed?.finishTransition()
            custom = null
            if (owned) nativeEffect?.let { retryMaterial.record(view?.background, it) }
            val owner = controller.get()
            if (owner != null && ownsHolder(owner) && !pendingAod.getBoolean(lazyGet.invoke(aod.get(owner)))) {
                retryMaterial.take(view?.background)?.let { value -> unmodified {
                    val restoreContext = if (alwaysDark && !SafeModeRuntime.blocked && tiny.invoke(null, header.context) == false) dark(value.context) else value.context
                    value.method.invoke(value.receiver, header, restoreContext)
                    nativeBackground = view?.background
                    // Reapplication can allocate another drawable; update always-dark ownership.
                    effect.clear()
                    if (restoreContext !== value.context) effect.record(view?.background, value)
                } }
            }
            if (owned && owner != null && ownsHolder(owner)) foreground.invoke(owner)
        }

        fun detachArtwork() {
            request.invalidate()
            discardArtwork()
            nativeEffect = null; nativeBackground = null
            controller.clear()
        }

        private fun discardArtwork() {
            job.cancel()
            restoreArtwork()
            processed = null
        }

        fun backgroundView(): View? = getHolder.invoke(header)?.let { background.get(it) as? View }
        fun observe() {
            if (observer == null && header.isAttachedToWindow) {
                observer = header.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            }
        }
        override fun onViewAttachedToWindow(v: View) {
            observe()
            try {
                controller.get()?.takeIf { ownsHolder(it) }?.let { foreground.invoke(it) }
                updateArtwork(force = true)
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
        override fun onViewDetachedFromWindow(v: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            job.cancel(); request.invalidate(); processed?.finishTransition()
            // No detached-view rendering work. The retained ownership is checked on
            // reattach; the native repeatWhenAttached binder refreshes material state.
        }
        override fun onPreDraw(): Boolean {
            try {
                syncHolder()
                val current = header.resources.configuration
                if (current != configuration) {
                    val changed = configuration != null
                    configuration = Configuration(current)
                    isTiny = tiny.invoke(null, header.context) != false
                    if (changed) { discardArtwork(); request.invalidate() }
                }
                if (!restoreFailed) {
                    if (artworkMode != 0) { updateArtwork(); updateVisual() }
                    if (SafeModeRuntime.blocked || isTiny) restoreSafely()
                }
            } catch (failure: Throwable) {
                fail(failure)
            }
            return true
        }

        private fun ownsHolder(owner: Any): Boolean {
            val current = holder.get(owner) ?: return false
            return current === boundHolder.get() && current === getHolder.invoke(header) &&
                (player.get(current) as? View)?.parent === header
        }

        private fun fail(failure: Throwable) {
            if (restoreFailed) return
            restoreFailed = true
            ModuleLog.error("$FEATURE: native color restoration failed", failure)
            SafeModeRuntime.trip("Hook failed: media color restoration")
        }

        private fun restoreSafely() {
            try {
                val owner = controller.get()
                // Preserve native AOD transition ownership; retry on the next frame.
                if (owner != null && pendingAod.getBoolean(lazyGet.invoke(aod.get(owner)))) return
                unmodified {
                    restoreArtwork()
                    effect.take(backgroundView()?.background)?.let { it.method.invoke(it.receiver, header, it.context) }
                    if (colorsChanged && owner != null && ownsHolder(owner)) {
                        foreground.invoke(owner)
                    }
                    colorsChanged = false
                }
            } catch (failure: Throwable) {
                fail(failure)
            }
        }
    }

    private fun binding(view: Any?): Binding? {
        val header = (view as? View)?.takeIf { headerType.isInstance(it) } ?: return null
        return states[header]?.get()?.also { it.syncHolder() } ?: Binding(header).also {
            states[header] = WeakReference(it)
            it.syncHolder()
            header.addOnAttachStateChangeListener(it)
            it.observe()
        }
    }

    private fun supported(source: Context): Boolean {
        if (!ready || nested.get() == true || Looper.myLooper() != Looper.getMainLooper()) return false
        val version = hostVersion ?: source.packageManager.getPackageInfo("com.android.systemui", 0).longVersionCode.also { hostVersion = it }
        return MediaDarkPolicy.enabled(version, true, false, SafeModeRuntime.blocked)
    }

    private fun active(source: Context): Boolean = supported(source) && tiny.invoke(null, source) == false

    private fun dark(source: Context): Context {
        val config = source.resources.configuration
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) return source
        // Clone, never mutate the host resource configuration or its font-scale/locale.
        return source.createConfigurationContext(Configuration(config).apply { uiMode = MediaDarkPolicy.nightMode(uiMode) })
    }

    private fun <T> unmodified(block: () -> T): T {
        val old = nested.get()
        nested.set(true)
        return try { block() } finally { if (old == null) nested.remove() else nested.set(old) }
    }

    private fun install(loader: ClassLoader): Int {
        val effectMethods = MediaDarkTargets.effects.map { method(loader, it) }
        // Glass variants call this typed overload, whereas the binder uses the Object bridge.
        val blur = method(loader, MediaDarkTargets.typedBlur)
        val callers = (MediaDarkTargets.callers + if (artworkMode != 0) MediaDarkTargets.artworkCallers else emptyList()).map { method(loader, it) }
        val artworkMethods = if (artworkMode == 0) emptyList() else
            listOf(MediaDarkTargets.bind, MediaDarkTargets.detach, MediaDarkTargets.replaceHolder).map { method(loader, it) }
        if (!(callers + effectMethods).map { HookRuntime.deoptimize(it, "$FEATURE/${it.name}") }.all { it }) return 0
        val foregroundInstalled = HookRuntime.hook(foreground, "$FEATURE/foreground") { chain ->
            val owner = chain.thisObject ?: return@hook chain.proceed()
            val source = context.get(owner) as Context
            if (!active(source)) return@hook chain.proceed()
            val state = holder.get(owner)?.let { player.get(it) as? View }?.parent?.let(::binding) ?: return@hook chain.proceed()
            state.controller = WeakReference(owner)
            val next = if (alwaysDark || state.hasCustom && state.wantsArtwork()) dark(source) else source
            if (next === source) {
                state.colorsChanged = false
                return@hook chain.proceed()
            }
            state.colorsChanged = true
            unmodified {
                withMediaColorContext({ context.get(owner) as Context }, { context.set(owner, it) }, next) { chain.proceed() }
            }
        }
        val installed = (effectMethods + blur).count { method ->
            HookRuntime.hook(method, "$FEATURE/${method.declaringClass.simpleName}/${method.parameterTypes[0].simpleName}") { chain ->
                val source = chain.args[1] as? Context ?: return@hook chain.proceed()
                if (!active(source)) return@hook chain.proceed()
                val state = binding(chain.args[0]) ?: return@hook chain.proceed()
                state.beforeEffect()
                val next = if (alwaysDark) dark(source) else source
                if (next === source) state.effect.clear()
                val receiver = chain.thisObject ?: return@hook chain.proceed()
                val result = unmodified { chain.proceed(arrayOf(chain.args[0], next)) }
                if (next !== source) state.effect.record(state.backgroundView()?.background, Effect(method, receiver, source))
                state.afterEffect(Effect(method, receiver, source))
                result
            }
        }
        // Partially installed hooks remain pass-through; no half-dark card on installation failure.
        var artworkHooks = 0
        if (artworkMode != 0) {
            if (HookRuntime.hookAfter(artworkMethods[0], "${Artwork.FEATURE}/bind") { chain, result ->
                    val owner = chain.thisObject!!
                    // Tiny-screen binds still invalidate old media; they never start pixel work.
                    if (!supported(context.get(owner) as Context)) return@hookAfter result
                    val state = holder.get(owner)?.let { player.get(it) as? View }?.parent?.let(::binding)
                    state?.controller = WeakReference(owner)
                    state?.mediaBound = chain.args[0] != null
                    state?.updateArtwork(chain.args[0] == null || artworkChanged.getBoolean(owner))
                    result
                }) artworkHooks++
            if (HookRuntime.hook(artworkMethods[1], "${Artwork.FEATURE}/detach") { chain ->
                    val owner = chain.thisObject!!
                    if (!supported(context.get(owner) as Context)) return@hook chain.proceed()
                    holder.get(owner)?.let { player.get(it) as? View }?.parent?.let(::binding)?.detachArtwork()
                    chain.proceed()
                }) artworkHooks++
            if (HookRuntime.hook(artworkMethods[2], "${Artwork.FEATURE}/holder") { chain ->
                    val header = chain.thisObject as? View ?: return@hook chain.proceed()
                    if (supported(header.context)) states[header]?.get()?.beforeHolderChange(chain.args[0])
                    chain.proceed()
                }) artworkHooks++
        }
        ready = foregroundInstalled && installed == effectMethods.size + 1 && artworkHooks == artworkMethods.size
        return if (ready) installed + 1 + artworkHooks else 0
    }

    companion object {
        const val FEATURE = "media_card_always_dark"

        fun install(loader: ClassLoader, settings: HookSettings): Int = MediaDarkHooks(loader, settings).install(loader)
        private fun type(loader: ClassLoader, name: String) = requireNotNull(Reflect.loadClass(loader, name)) { name }
        private fun method(loader: ClassLoader, spec: SystemUiMethod) = requireNotNull(NotificationHooks.resolve(loader, spec)) { spec.toString() }
    }
}
