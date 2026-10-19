/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.res.Resources
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.NavigationGeometrySettings as Config
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

@TargetApi(30)
// 宿主 dimen ID 直接来自该版本资源表，模块本地 R 表中不存在这些条目。
@SuppressLint("ResourceType")
internal object NavigationGeometryHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = NavigationGeometryTargets
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, expected) ->
            (owner to name) to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        val dimension = Resources::class.java.getDeclaredMethod("getDimensionPixelSize", Int::class.javaPrimitiveType).apply {
            require(returnType == Int::class.javaPrimitiveType && !Modifier.isStatic(modifiers))
        }
        if (!(methods.values + dimension).all { HookRuntime.deoptimize(it, "${Config.FEATURE}/caller") }) return 0
        // Geometry belongs to a window lifecycle; do not split existing params and view
        // measurements by changing settings between native calls.
        val values = Config.config.associate { it.key to settings.number(it.key, 0, it.min, it.max) }
        val scope = ThreadLocal<NavigationGeometryPolicy.Geometry>()
        val ready = AtomicBoolean(false)
        fun checked(resources: Resources, id: Int): Boolean =
            resources.getResourcePackageName(id) == "android" &&
                resources.getResourceTypeName(id) == "dimen" &&
                resources.getResourceEntryName(id) == t.resources[id]
        val resource = HookRuntime.hookAfter(dimension, "${Config.FEATURE}/dimension") { chain, original ->
            val geometry = scope.get() ?: return@hookAfter original
            val id = chain.args[0] as? Int ?: return@hookAfter original
            if (!ready.get() || SafeModeRuntime.blocked || id !in t.resources) return@hookAfter original
            runCatching {
                val resources = chain.thisObject as? Resources ?: return@runCatching original
                if (!checked(resources, id)) original else if (id == 0x10502a4) geometry.frame else geometry.bar
            }.getOrDefault(original)
        }
        val hooks = listOf(t.params, t.height, t.measure).map { target ->
            HookRuntime.hook(methods.getValue(target), "${Config.FEATURE}/${target.name}") { chain ->
                if (!ready.get() || Looper.myLooper() != Looper.getMainLooper()) return@hook chain.proceed()
                val old = scope.get()
                val next = runCatching {
                    val owner = chain.thisObject ?: return@runCatching null
                    val context = if (target == t.params) fields.getValue(t.BAR to "mContext").get(owner) as? Context
                        else (owner as? View)?.context
                    context ?: return@runCatching null
                    val edge = fields.getValue(target.owner to "mEdgeBackGestureHandler").get(owner) ?: return@runCatching null
                    if (!NavigationGeometryPolicy.eligible(context.hostDisplayIdOrNull(),
                            fields.getValue(target.owner to "mNavBarMode").getInt(owner),
                            fields.getValue(t.EDGE to "mIsButtonForcedVisible").getBoolean(edge), true)) return@runCatching null
                    val rotation = if (target == t.params) (chain.args[0] as? Int)?.takeIf { it in 0..3 }
                        ?: context.display?.rotation else (owner as? View)?.display?.rotation
                    val landscape = NavigationGeometryPolicy.landscape(rotation ?: -1) ?: return@runCatching null
                    val resources = context.resources
                    if (!t.resources.keys.all { checked(resources, it) }) return@runCatching null
                    // Read native inputs with no active override, including nested measurements.
                    scope.remove()
                    val nativeBar = resources.getDimensionPixelSize(0x10502a8)
                    val nativeFrame = resources.getDimensionPixelSize(0x10502a4)
                    NavigationGeometryPolicy.resolve(
                        values.getValue(if (landscape) Config.barLandscape.key else Config.barPortrait.key),
                        values.getValue(if (landscape) Config.frameLandscape.key else Config.framePortrait.key),
                        resources.displayMetrics.density, nativeBar, nativeFrame)
                }.getOrNull()
                if (next == null) scope.remove() else scope.set(next)
                try { chain.proceed() } finally { if (old == null) scope.remove() else scope.set(old) }
            }
        }
        ready.set(resource && hooks.all { it })
        return if (ready.get()) 4 else 0
    }
}
