/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.os.Looper
import android.text.Spanned
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import android.widget.TextView
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object SliderValueHooks {
    fun install(loader: ClassLoader, settings: HookSettings, feature: String): Int {
        require(feature in SliderValuePolicy.features)
        return Adapter(loader, settings, feature).install()
    }

    private class Adapter(val loader: ClassLoader, val settings: HookSettings, val feature: String) {
        val targets = SliderValueTargets
        val owner = if (feature == SliderValuePolicy.BRIGHTNESS) targets.BRIGHTNESS else targets.VOLUME
        val methods = mutableMapOf<SystemUiMethod, Method>()
        val fields = mutableMapOf<String, Field>()
        val entries = WeakHashMap<Any, Label>()
        val active = ThreadLocal<Any?>()
        val ready = AtomicBoolean(false)
        val warned = AtomicBoolean(false)
        var supportedHost: Boolean? = null

        fun install(): Int {
            for (spec in (targets.methods(owner) + targets.callers(owner)).distinct())
                methods[spec] = NotificationHooks.resolve(loader, spec) ?: return 0
            for ((type, name, expected) in targets.fields(owner)) {
                fields[name] = Class.forName(type, false, loader).getDeclaredField(name).apply {
                    require(this.type.name == expected); isAccessible = true
                }
            }
            val interceptionPaths = targets.updates(owner) + targets.cleanup(owner) + targets.callers(owner)
            // Queries need exact resolution, not deoptimization (Provider.get is abstract).
            if (!interceptionPaths.map { HookRuntime.deoptimize(methods.getValue(it), "$feature/caller") }.all { it }) return 0
            var count = 0
            for (spec in targets.updates(owner)) {
                if (HookRuntime.hook(methods.getValue(spec), "$feature/${spec.name}") { chain ->
                        val controller = chain.thisObject ?: return@hook chain.proceed()
                        if (!ready.get() || Looper.myLooper() != Looper.getMainLooper() || active.get() === controller)
                            return@hook chain.proceed()
                        val previous = active.get()
                        active.set(controller)
                        try {
                            // Remove only our previous text before native diff-based super-volume setters.
                            safely { entries[controller]?.restore() }
                            val result = chain.proceed()
                            safely { refresh(controller) }
                            result
                        } finally {
                            if (previous == null) active.remove() else active.set(previous)
                        }
                    }) count++
            }
            for (spec in targets.cleanup(owner)) {
                if (HookRuntime.hook(methods.getValue(spec), "$feature/${spec.name}") { chain ->
                        val controller = chain.thisObject ?: return@hook chain.proceed()
                        if (Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                        val previous = active.get()
                        active.set(controller)
                        try {
                            safely { entries.remove(controller)?.dispose() }
                            chain.proceed()
                        } finally {
                            if (previous == null) active.remove() else active.set(previous)
                        }
                    }) count++
            }
            ready.set(count == targets.updates(owner).size + targets.cleanup(owner).size)
            return if (ready.get()) count else 0
        }

        fun safely(block: () -> Unit) {
            runCatching(block).onFailure {
                if (warned.compareAndSet(false, true)) ModuleLog.error("$feature: label update skipped (logic reviewed; not built or device-tested)", it)
            }
        }

        fun refresh(controller: Any) {
            val holder = methods.getValue(targets.holder(owner)).invoke(controller)
            val text = holder?.let { methods.getValue(targets.top).invoke(it) as? TextView }
            val old = entries[controller]
            if (old?.view?.get() !== text) entries.remove(controller)?.dispose()
            if (holder == null || text == null) return
            val label = entries[controller] ?: Label(controller, text).also {
                entries[controller] = it
                text.addOnAttachStateChangeListener(it)
            }
            label.restore()
            if (!ready.get() || SafeModeRuntime.blocked || !settings.isOn(feature)) return
            val slider = methods.getValue(targets.slider).invoke(holder) as? SeekBar ?: return
            val context = text.context
            val accessibility = context.getSystemService(AccessibilityManager::class.java) ?: return
            val supported = supportedHost ?: run {
                val pm = context.packageManager
                SliderValuePolicy.supported(pm.getPackageInfo("com.android.systemui", 0).compatibleVersionCode,
                    pm.getPackageInfo(SystemUiPluginHooks.PACKAGE, 0).compatibleVersionCode).also { supportedHost = it }
            }
            if (!supported ||
                !text.isAttachedToWindow || text.display?.displayId != 0 || !slider.isShown || !slider.isEnabled || slider.display?.displayId != 0 ||
                accessibility.isTouchExplorationEnabled || methods.getValue(targets.mirror).invoke(holder) == true ||
                methods.getValue(targets.disabled).invoke(holder) == true) return
            val provider = fields.getValue("secondaryPanelRouter").get(controller) ?: return
            val router = methods.getValue(targets.provider).invoke(provider) ?: return
            if (methods.getValue(targets.mainPanel).invoke(router) != true) return
            // An existing native label (notably super-volume) always wins, including its accessibility label.
            if (text.visibility == View.VISIBLE || text.text is Spanned) return
            val value = if (owner == targets.BRIGHTNESS) {
                if (fields.getValue("isInEditMode").getBoolean(controller)) return
                SliderValuePolicy.brightness(methods.getValue(targets.value).invoke(slider) as Int, slider.min, slider.max)
            } else {
                val target = methods.getValue(targets.targetValue).invoke(controller) as Int
                val max = fields.getValue("streamMaxVolume").getInt(controller)
                if (target !in slider.min..slider.max) return
                val level = methods.getValue(targets.volumeLevel).invoke(controller, target) as Int
                SliderValuePolicy.volume(level, max, fields.getValue("muted").getBoolean(controller))
            } ?: return
            label.show(value)
        }

        inner class Label(controller: Any, text: TextView) : View.OnAttachStateChangeListener {
            val controller = WeakReference(controller)
            val view = WeakReference(text)
            var originalText = ""
            var originalVisibility = View.GONE
            var applied: String? = null

            fun show(value: String) {
                val text = view.get() ?: return
                // Rich text is excluded; keep no spans that could retain the host view/controller.
                originalText = text.text.toString()
                originalVisibility = text.visibility
                applied = value
                text.text = value
                text.visibility = View.VISIBLE
            }

            fun restore() {
                val value = applied ?: return
                applied = null
                val text = view.get() ?: return
                // Do not overwrite a newer native text/visibility write.
                if (text.text.toString() == value && text.visibility == View.VISIBLE) {
                    text.text = originalText
                    text.visibility = originalVisibility
                }
                originalText = ""
            }

            fun dispose() { restore(); view.get()?.removeOnAttachStateChangeListener(this) }
            override fun onViewAttachedToWindow(view: View) = safely {
                controller.get()?.let { if (entries[it] === this && active.get() !== it) refresh(it) }
            }
            override fun onViewDetachedFromWindow(view: View) = safely { restore() }
        }
    }
}
