/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference

/** MT yglu33cw, MiSound 260903: service -> B/u/z/m -> native ball click -> y. */
internal object MiSoundVolumeHooks {
    private var receiver: BroadcastReceiver? = null
    private var service = WeakReference<Service>(null)

    fun install(loader: ClassLoader): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val type = Reflect.loadClass(loader, AppVolumeSettings.SERVICE) ?: return 0
        val controller = Reflect.loadClass(loader, "com.miui.misound.playervolume.a") ?: return 0
        val controllerField = type.getDeclaredField("e").apply { isAccessible = true }
        if (controllerField.type != controller) return 0
        val status = controller.getDeclaredField("a").apply { isAccessible = true }
        val button = controller.getDeclaredField("n").apply { isAccessible = true }
        val page = controller.getDeclaredField("o").apply { isAccessible = true }
        val sources = controller.getDeclaredField("t").apply { isAccessible = true }
        if (status.type != Int::class.javaPrimitiveType || !View::class.java.isAssignableFrom(button.type) ||
            !View::class.java.isAssignableFrom(page.type) || !List::class.java.isAssignableFrom(sources.type)) return 0

        fun register(host: Service) {
            // Obfuscated controller members are only audited for this target build.
            if (host.packageManager.getPackageInfo(AppVolumeSettings.PACKAGE, 0).longVersionCode != 260903L) {
                ModuleLog.warn("app_volume MiSound version not audited; native panel retained")
                return
            }
            service = WeakReference(host)
            if (receiver != null) return
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != AppVolumeSettings.ACTION || !isOrderedBroadcast) return
                    resultCode = Activity.RESULT_CANCELED
                    runCatching {
                        if (MediaPlayback.locked(context) || !MediaPlayback.active(context)) return@runCatching
                        val current = service.get() ?: return@runCatching
                        val target = controllerField.get(current) ?: return@runCatching
                        // Never reset the native state machine or synthesize a list of applications.
                        if ((sources.get(target) as? List<*>)?.isNotEmpty() != true) return@runCatching
                        if (status.getInt(target) == 2000) (button.get(target) as? View)?.performClick()
                        if (status.getInt(target) == 5000 && (page.get(target) as? View)?.isAttachedToWindow == true) {
                            resultCode = Activity.RESULT_OK
                        }
                    }.onFailure { ModuleLog.error("app volume native click failed", it) }
                }
            }
            host.applicationContext.registerReceiver(listener, IntentFilter(AppVolumeSettings.ACTION),
                AppVolumeSettings.SENDER_PERMISSION, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
            receiver = listener
        }

        var count = 0
        val start = type.getDeclaredMethod("onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        if (HookRuntime.hookAfter(start, "app_volume/misound/serviceStart") { chain, original ->
                (chain.thisObject as? Service)?.let(::register)
                original
            }) count++
        val destroy = type.getDeclaredMethod("onDestroy")
        if (HookRuntime.hookAfter(destroy, "app_volume/misound/serviceDestroy") { chain, original ->
                if (service.get() === chain.thisObject) {
                    receiver?.let { listener -> Reflect.attempt { (chain.thisObject as Service).applicationContext.unregisterReceiver(listener) } }
                    receiver = null
                    service.clear()
                }
                original
            }) count++
        // Do not suppress the native entry unless the replacement launch bridge is installed.
        if (count == 2) count += MiSoundCardHooks.install(loader, controller)
        return count
    }
}
