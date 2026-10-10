/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.animation.ValueAnimator
import android.content.Context
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.LockscreenWallpaperSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.util.concurrent.atomic.AtomicBoolean

internal object LockscreenWallpaperHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val targets = LockscreenWallpaperTargets
        val methods = targets.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val owner = methods.getValue(targets.animation).declaringClass
        val fields = targets.fields.mapValues { (name, type) ->
            owner.getDeclaredField(name).apply { require(this.type.name == type); isAccessible = true }
        }
        val constructor = Class.forName(targets.INTERPOLATE, false, loader)
            .getDeclaredConstructor(Int::class.javaPrimitiveType, FloatArray::class.java)
        if (!listOf(targets.animation, targets.config, targets.caller).all {
                HookRuntime.deoptimize(methods.getValue(it), "lockscreen_wallpaper_transition/${it.name}")
            }) return 0
        val ready = AtomicBoolean(false)
        val logged = AtomicBoolean(false)
        val scope = LockscreenWallpaperScope()
        var version: Long? = null
        val wake = LockscreenWallpaperSettings.duration(true, settings.string(LockscreenWallpaperSettings.WAKE))
        val sleep = LockscreenWallpaperSettings.duration(false, settings.string(LockscreenWallpaperSettings.SLEEP))
        fun frame(host: Any, show: Boolean, needAnimation: Boolean): LockscreenWallpaperScope.Frame? {
            if (Looper.myLooper() != Looper.getMainLooper() || !needAnimation ||
                !settings.isOn(LockscreenWallpaperSettings.FEATURE) || !ValueAnimator.areAnimatorsEnabled()) return null
            val context = fields.getValue("context").get(host) as? Context ?: return null
            val root = fields.getValue("keyguardRootView").get(host) as? View ?: return null
            val hostVersion = version ?: runCatching {
                context.packageManager.getPackageInfo("com.android.systemui", 0).compatibleVersionCode
            }.getOrNull()?.also { version = it }
            if (hostVersion != 202602260L || !root.isAttachedToWindow || root.display?.displayId != 0) return null
            fun flag(name: String) = fields.getValue(name).getBoolean(host)
            fun query(spec: SystemUiMethod, field: String): Boolean =
                methods.getValue(spec).invoke(fields.getValue(field).get(host)) as Boolean
            if (!LockscreenWallpaperPolicy.eligible(hostVersion, true, true, needAnimation, true,
                    root.isAttachedToWindow, root.display?.displayId, flag("isDefaultTheme"), flag("keyguardShowing"),
                    query(targets.fullAod, "miuiFullAodManager"), query(targets.depth, "keyguardCommonSettingObserver"),
                    query(targets.video, "miuiKeyguardWallPaperManager") || query(targets.videoDepth, "miuiKeyguardWallPaperManager"),
                    flag("isFlipFold"), flag("keyguardOccluded"), flag("sleepFromGone"),
                    flag("keyguardBouncerShowing") || flag("isBouncerShowingWhenStartedGoingToSleep"),
                    flag("isGoingToDismissKeyguard"), flag("isSuperSavePowerMode"))) return null
            val native = fields.getValue(if (show) "localAodShowEaseStyle" else "localAodHideEaseStyle").get(host)
                ?: return null
            return LockscreenWallpaperScope.Frame(native, if (show) wake else sleep)
        }
        val configHook = HookRuntime.hook(methods.getValue(targets.config), "lockscreen_wallpaper_transition/config") { chain ->
            if (!ready.get()) return@hook chain.proceed()
            val duration = scope.take(chain.args[0]) ?: return@hook chain.proceed()
            // The new ease belongs to this animation. Never restore its duration after to():
            // Folme reads it asynchronously, so mutating a shared host ease would race frames.
            val ease = constructor.newInstance(20, floatArrayOf(1f))
            methods.getValue(targets.duration).invoke(ease, duration)
            if (logged.compareAndSet(false, true)) ModuleLog.info("lockscreen_wallpaper_transition: native wallpaper config matched (device acceptance pending)")
            chain.proceed(arrayOf(ease))
        }
        val animationHook = HookRuntime.hook(methods.getValue(targets.animation), "lockscreen_wallpaper_transition/animation") { chain ->
            if (!ready.get()) return@hook chain.proceed()
            // A nested excluded call must mask, not inherit, an outer wallpaper scope.
            scope.withFrame(null) {
                val frame = frame(chain.thisObject!!, chain.args[0] == true, chain.args[3] == true)
                scope.withFrame(frame) { chain.proceed() }
            }
        }
        ready.set(configHook && animationHook)
        return if (ready.get()) 2 else 0
    }
}
