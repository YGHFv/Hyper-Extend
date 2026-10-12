/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.NotificationBlendSettings
import io.github.YGHFv.HyperExtend.core.compatibleVersionCode
import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.util.concurrent.atomic.AtomicBoolean

internal object NotificationBlendHooks {
    private data class Scope(val context: Context, val root: View, val scene: NotificationBlendScene, val allowed: Boolean)
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = NotificationBlendTargets
        val id = NotificationBlendSettings.FEATURE
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        if (!methods.values.all { HookRuntime.deoptimize(it, "$id/caller") }) return 0
        val ready = AtomicBoolean(false)
        val scope = NotificationBlendScope<Scope>()
        var version: Long? = null
        fun main() = Looper.myLooper() == Looper.getMainLooper()
        val blend = HookRuntime.hook(methods.getValue(t.blend), "$id/colors") { chain ->
            val active = scope.current
            if (!ready.get() || SafeModeRuntime.blocked || !main() || active?.allowed != true) return@hook chain.proceed()
            val context = chain.args[0] as? Context ?: return@hook chain.proceed()
            val view = chain.args[1] as? View ?: return@hook chain.proceed()
            // Always-dark uses a cloned ConfigurationContext inside the same effect.
            // Bind to the effect's actual view tree, not the original Context identity.
            var ancestor: View? = view
            var depth = 0
            while (ancestor != null && ancestor !== active.root && depth++ < 32) ancestor = ancestor.parent as? View
            if (ancestor !== active.root || context.applicationInfo.uid != active.context.applicationInfo.uid ||
                context.packageName != active.context.packageName) return@hook chain.proceed()
            val native = chain.args[3] as? IntArray ?: return@hook chain.proceed()
            val next = runCatching {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night != Configuration.UI_MODE_NIGHT_NO && night != Configuration.UI_MODE_NIGHT_YES) null
                else NotificationBlendPolicy.colors(native, active.scene, night == Configuration.UI_MODE_NIGHT_YES)
            }.getOrNull() ?: return@hook chain.proceed()
            val args = chain.args.toTypedArray()
            args[3] = next
            // Change only color slots; modes, round rect, blur capability and clear remain native.
            chain.proceed(args)
        }
        val scopes = t.scopes.map { (target, scene) ->
            HookRuntime.hook(methods.getValue(target), "$id/${target.owner.substringAfterLast('.')}") { chain ->
                if (!ready.get() || !main()) return@hook chain.proceed()
                val context = chain.args[1] as? Context ?: return@hook chain.proceed()
                val view = chain.args[0] as? View ?: return@hook chain.proceed()
                val allowed = runCatching {
                    val host = version ?: context.packageManager.getPackageInfo("com.android.systemui", 0)
                        .compatibleVersionCode.also { version = it }
                    NotificationBlendPolicy.eligible(scene, settings.isOn(id),
                        settings.isOn(NotificationBlendSettings.KEYGUARD), host,
                        view.display?.displayId ?: context.hostDisplayIdOrNull(), true)
                }.getOrDefault(false)
                scope.within(Scope(context, view, scene, allowed)) { chain.proceed() }
            }
        }
        ready.set(blend && scopes.all { it })
        return if (ready.get()) 1 + scopes.size else 0
    }
}
