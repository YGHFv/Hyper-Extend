/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.MediaCardSettings as Settings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap

internal object MediaCardHooks {
    fun install(loader: ClassLoader, settings: HookSettings): List<String> = buildList {
        installSystemUiFeature(settings, Settings.LAYOUT) { layout(loader, settings) }
        installSystemUiFeature(settings, Settings.TEXT_SIZE) { textSize(loader, settings) }
        if (isNotEmpty()) {
            // R8 inlines holder construction; keep the verified creation/refresh callers observable.
            listOf(SystemUiTargets.mediaReinflate, SystemUiTargets.mediaBoundsChanged).forEach { spec ->
                NotificationHooks.resolve(loader, spec)?.let { HookRuntime.deoptimize(it, "media_card/${spec.name}") }
            }
        }
    }

    private fun layout(loader: ClassLoader, settings: HookSettings): Int {
        val options = MediaLayoutOptions(
            album = Settings.mode(settings.string(Settings.ALBUM)),
            order = Settings.mode(settings.string(Settings.ORDER)),
            leftAligned = settings.isOn(Settings.LEFT_ALIGNED),
            hideSeamless = settings.isOn(Settings.HIDE_SEAMLESS),
            titleMargin = Settings.value(settings.string(Settings.TITLE_MARGIN), Settings.titleMargin),
            artistMargin = Settings.value(settings.string(Settings.ARTIST_MARGIN), Settings.artistMargin),
        )
        val load = resolve(loader, SystemUiTargets.mediaLoadLayout)
        val context = field(load.declaringClass, "context", Context::class.java)
        val bridge = ConstraintBridge(loader)
        val normal = field(load.declaringClass, "normalLayout", bridge.type)
        val album = field(load.declaringClass, "normalAlbumLayout", bridge.type)
        val views = if (options.hideSeamless) HolderAccess(loader) else null
        val seamless = views?.let { field(it.holderType, "seamless", View::class.java) }
        // loadLayout handles initial binding/layout refreshes; only setSeamless can show it again.
        // Do not register attach/updateLayout twice when both media features are enabled.
        val refreshes = if (views == null) emptyList() else listOf(resolve(loader, SystemUiTargets.mediaSeamless))
        var count = if (HookRuntime.hookAfter(load, "${Settings.LAYOUT}/loadLayout") { chain, original ->
                if (options.changed) {
                    val owner = chain.thisObject!!
                    val hostContext = context.get(owner) as Context
                    val ids = MediaLayoutPolicy.resourceNames.associateWith { name ->
                        hostContext.resources.getIdentifier(name, "id", "com.android.systemui")
                    }
                    val density = hostContext.resources.displayMetrics.density
                    val oldNormal = normal.get(owner)
                    val oldAlbum = album.get(owner)
                    // Prepare both copies before publishing; a missing resource/API leaves originals intact.
                    val newNormal = bridge.copyWith(oldNormal, MediaLayoutPolicy.edits(options, ids, density))
                    val newAlbum = bridge.copyWith(oldAlbum, MediaLayoutPolicy.edits(options, ids, density, albumLayer = true))
                    try {
                        normal.set(owner, newNormal)
                        album.set(owner, newAlbum)
                    } catch (failure: Throwable) {
                        normal.set(owner, oldNormal)
                        album.set(owner, oldAlbum)
                        throw failure
                    }
                }
                original
            }) 1 else 0
        count += refreshes.count { method ->
            HookRuntime.hookAfter(method, "${Settings.LAYOUT}/${method.name}") { chain, original ->
                views!!.normalHolder(chain.thisObject!!)?.let { holder ->
                    (seamless!!.get(holder) as? View)?.visibility = View.GONE
                }
                original
            }
        }
        return count + MediaButtonSizing.install(loader, settings)
    }

    private fun textSize(loader: ClassLoader, settings: HookSettings): Int {
        val views = HolderAccess(loader)
        val sizes = listOf(
            "titleText" to Settings.titleSize, "artistText" to Settings.artistSize,
            "elapsedTimeView" to Settings.timeSize, "totalTimeView" to Settings.timeSize,
        ).map { (name, slider) ->
            field(views.holderType, name, TextView::class.java) to Settings.value(settings.string(slider.key), slider) / 10f
        }
        val methods = listOf(SystemUiTargets.mediaAttach, SystemUiTargets.mediaUpdateLayout).map { resolve(loader, it) }
        val originalTimeSizes = WeakHashMap<Any, List<Float>>()
        return methods.count { method ->
            HookRuntime.hookAfter(method, "${Settings.TEXT_SIZE}/${method.name}") { chain, original ->
                views.holder(chain.thisObject!!)?.let { holder ->
                    val targets = sizes.map { (field, size) -> (field.get(holder) as TextView) to size }
                    if (views.isTiny(chain.thisObject!!)) {
                        // updateLayout restores title/artist appearances itself, but not time text sizes.
                        originalTimeSizes.remove(holder)?.let { originalSizes ->
                            targets.drop(2).zip(originalSizes).forEach { (target, size) ->
                                target.first.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
                            }
                        }
                    } else {
                        originalTimeSizes.getOrPut(holder) { targets.drop(2).map { it.first.textSize } }
                        targets.forEach { (view, size) -> view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size) }
                    }
                }
                original
            }
        }
    }

    private class HolderAccess(loader: ClassLoader) {
        val holderType = requireNotNull(Reflect.loadClass(loader, SystemUiTargets.MEDIA_HOLDER))
        private val notificationType = requireNotNull(Reflect.loadClass(loader, SystemUiTargets.MEDIA_NOTIFICATION))
        private val controllerType = requireNotNull(Reflect.loadClass(loader, SystemUiTargets.MEDIA_CONTROLLER))
        private val notificationHolder = field(notificationType, "mediaViewHolder", holderType)
        private val controllerHolder = field(controllerType, "holder", holderType)
        private val notificationContext = field(notificationType, "context", Context::class.java)
        private val controllerContext = field(controllerType, "context", Context::class.java)
        private val tiny = resolve(loader, SystemUiTargets.flipTinyScreen)

        fun isTiny(owner: Any): Boolean {
            val notification = notificationType.isInstance(owner)
            val context = (if (notification) notificationContext else controllerContext).get(owner) as Context
            return tiny.invoke(null, context) != false
        }

        fun holder(owner: Any): Any? = (if (notificationType.isInstance(owner)) notificationHolder else controllerHolder).get(owner)

        fun normalHolder(owner: Any): Any? = if (isTiny(owner)) null else holder(owner)
    }

    private class ConstraintBridge(loader: ClassLoader) {
        val type = requireNotNull(Reflect.loadClass(loader, "androidx.constraintlayout.widget.ConstraintSet"))
        private val constructor = type.getDeclaredConstructor().apply { isAccessible = true }
        private val clone = type.getDeclaredMethod("clone", type).apply { isAccessible = true }
        private val methods: Map<String, Method> = mapOf(
            "connect" to 4, "clear" to 2, "setMargin" to 3, "setGoneMargin" to 3,
            "setVisibility" to 2, "setHorizontalChainStyle" to 2,
        ).mapValues { (name, count) ->
            type.getDeclaredMethod(name, *Array(count) { Int::class.javaPrimitiveType!! }).apply { isAccessible = true }
        }

        fun copyWith(source: Any?, edits: List<MediaConstraintEdit>): Any {
            require(type.isInstance(source))
            val copy = constructor.newInstance()
            clone.invoke(copy, source)
            edits.forEach { edit -> methods.getValue(edit.method).invoke(copy, *edit.arguments.toTypedArray()) }
            return copy
        }
    }

    private fun resolve(loader: ClassLoader, spec: SystemUiMethod): Method =
        requireNotNull(NotificationHooks.resolve(loader, spec)) { "Missing ${spec.owner}.${spec.name}" }

    private fun field(owner: Class<*>, name: String, type: Class<*>): Field =
        requireNotNull(Reflect.findField(owner, name)?.takeIf { type.isAssignableFrom(it.type) }) { "Missing ${owner.name}.$name" }
}
