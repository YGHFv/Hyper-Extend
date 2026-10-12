/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.view.View
import io.github.YGHFv.HyperExtend.core.ICON_SLOT_NAMES
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.feature.systemui.NotificationHooks
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal object IconBlockListHooks {
    private const val FEATURE = "status_bar_icons"

    private fun modes(settings: HookSettings): Map<String, Int> = buildMap {
        if (!settings.isOn(FEATURE)) return@buildMap
        ICON_SLOT_NAMES.forEach { (key, slots) ->
            val mode = settings.number(key, 0)
            if (mode in listOf(1, 2, 11, 12)) slots.forEach { put(it, mode) }
        }
        mapOf("stealth" to "stealth", "hide_mute" to "mute",
            "hide_speakerphone" to "speakerphone", "hide_call_record" to "record").forEach { (key, slot) ->
            if (settings.isOn("$FEATURE.$key")) put(slot, 2)
        }
    }

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (modes(settings).isEmpty()) return 0
        val t = IconBlockListTargets
        val methods = (listOf(t.setBlockList, t.addIgnored) + t.callers).associateWith {
            NotificationHooks.resolve(loader, it) ?: return 0
        }
        val group = StatusIconHost.field(loader, t.MANAGER, "mGroup", "android.view.ViewGroup")
        val location = StatusIconHost.field(loader, t.MANAGER, "mLocation",
            "com.android.systemui.statusbar.phone.StatusBarLocation")
        val ignored = StatusIconHost.field(loader, t.CONTAINER, "ignoredSlots", "java.util.List")
        // Resolve without initializing the host's defaults; read only in a native UI call.
        val defaults = Class.forName(t.DEFAULTS, false, loader)
        val lists = listOf("RIGHT_BLOCK_LIST", "CONTROL_CENTER_BLOCK_LIST").map { name ->
            defaults.getDeclaredField(name).apply {
                require(Modifier.isStatic(modifiers) && type == ArrayList::class.java)
                isAccessible = true
            }
        }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$FEATURE/blocks-caller") }) return 0
        val host = StatusIconHost()
        val ready = AtomicBoolean(false)
        val ownership = WeakHashMap<View, OwnedList>()
        @Suppress("UNCHECKED_CAST")
        fun current(view: View): MutableList<String>? {
            if (!ignored.declaringClass.isInstance(view)) return null
            val list = ignored.get(view) as? MutableList<*> ?: return null
            return if (list.all { it is String }) list as MutableList<String> else null
        }
        fun restore(view: View) {
            val old = ownership.remove(view) ?: return
            val list = current(view) ?: return
            StatusIconPolicy.restoreList(list, old.native, old.applied)?.let { native ->
                list.clear()
                list.addAll(native)
                view.requestLayout()
            }
        }
        fun update(view: View, center: Boolean) {
            val list = current(view) ?: return
            val native = list.toList()
            val applied = StatusIconPolicy.blockList(native, modes(HookRuntime.settings()), center)
            if (native == applied) return
            list.clear()
            list.addAll(applied)
            ownership[view] = OwnedList(native, applied)
            view.requestLayout()
        }
        val hooks = listOf(t.setBlockList, t.addIgnored).map { target ->
            HookRuntime.hook(methods.getValue(target), "$FEATURE/blocks/${target.name}") { chain ->
                if (!ready.get()) return@hook chain.proceed()
                val view = if (target == t.setBlockList) group.get(chain.thisObject) as? View
                    else chain.thisObject as? View
                if (view == null || !host.eligible(view)) return@hook chain.proceed()
                val input = chain.args[0] as? List<*> ?: return@hook chain.proceed()
                if (input.any { it !is String }) return@hook chain.proceed()
                val center = (if (target == t.setBlockList) {
                    StatusIconPolicy.controlCenter((location.get(chain.thisObject) as? Enum<*>)?.name)
                } else when {
                    input === lists[0].get(null) -> false
                    input === lists[1].get(null) -> true
                    else -> null
                }) ?: return@hook chain.proceed()
                restore(view)
                if (target == t.addIgnored) {
                    // addIgnoredSlots is incremental. Restore only our owned previous delta,
                    // let native add its current input, then apply the new per-view policy.
                    val result = chain.proceed()
                    update(view, center)
                    return@hook result
                }
                @Suppress("UNCHECKED_CAST")
                val replacement = StatusIconPolicy.blockList(input as List<String>, modes(HookRuntime.settings()), center)
                // Native setters own the copy, refresh, invalidation and list lifetime.
                // Minimalism, user hide lists, icon availability and island limits stay native.
                val result = if (replacement == input) chain.proceed()
                    else chain.proceed(arrayOf(replacement))
                // Modern Wi-Fi's setBlocked is a no-op. Its actual parent layout consumes
                // ignoredSlots, including keyguard/QS_FAKE where native never fills that list.
                update(view, center)
                result
            }
        }
        ready.set(hooks.all { it })
        return if (ready.get()) hooks.size else 0
    }

    private data class OwnedList(val native: List<String>, val applied: List<String>)
}
