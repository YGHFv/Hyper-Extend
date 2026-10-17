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
import android.view.WindowManager
import io.github.YGHFv.HyperExtend.core.AppVolumeSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.ref.WeakReference

/** The service owns the data bridge; SystemUI owns the only visible panel. */
internal object MiSoundVolumeHooks {
    private var receiver: BroadcastReceiver? = null
    private var service = WeakReference<Service>(null)

    fun install(loader: ClassLoader): Int {
        if (Build.VERSION.SDK_INT < 33) return 0
        val type = Reflect.loadClass(loader, AppVolumeSettings.SERVICE) ?: return 0
        val controller = Reflect.loadClass(loader, "com.miui.misound.playervolume.a") ?: return 0
        val controllerField = type.getDeclaredField("e").apply { isAccessible = true }
        if (controllerField.type != controller) return 0
        val backend = MiSoundAppVolumeBackend(loader, controller)
        val controllerContext = controller.getDeclaredField("c").apply { isAccessible = true }
        val ball = controller.getDeclaredField("m").apply { isAccessible = true }
        val dataOnly = ThreadLocal<Boolean>()
        var ready = false
        var lastQueryFailure: String? = null

        // 版本号只用于诊断；真正的门禁是下面五个 hook 和反优化是否完整。
        fun supported(): Boolean = ready

        fun register(host: Service) {
            if (!supported()) return
            val versions = AppVolumeHostCompatibility.versions(host)
            ModuleLog.info("app_volume host versions: misound=${versions.misound} plugin=${versions.plugin}; structural validation is authoritative")
            service = WeakReference(host)
            if (receiver != null) return
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action !in listOf(AppVolumeBridge.QUERY, AppVolumeBridge.WRITE, AppVolumeBridge.END) || !isOrderedBroadcast) return
                    resultCode = Activity.RESULT_CANCELED
                    setResultExtras(AppVolumeBridge.failure("unavailable"))
                    runCatching {
                        val current = service.get() ?: return@runCatching
                        val target = controllerField.get(current) ?: return@runCatching
                        backend.handle(current, target, intent)?.let {
                            setResultExtras(it)
                            val failure = AppVolumeBridge.failureReason(it)
                            if (failure == null) resultCode = Activity.RESULT_OK
                            if (intent.action == AppVolumeBridge.QUERY && failure != lastQueryFailure) {
                                if (failure != null) ModuleLog.warn("app_volume query rejected: $failure")
                                lastQueryFailure = failure
                            }
                        }
                    }.onFailure {
                        setResultExtras(AppVolumeBridge.failure("backend_error"))
                        ModuleLog.error("app volume data request failed", it)
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(AppVolumeBridge.QUERY); addAction(AppVolumeBridge.WRITE); addAction(AppVolumeBridge.END)
            }
            host.applicationContext.registerReceiver(listener, filter,
                AppVolumeSettings.SENDER_PERMISSION, Handler(Looper.getMainLooper()), Context.RECEIVER_EXPORTED)
            receiver = listener
        }

        var count = 0
        val start = type.getDeclaredMethod("onStartCommand", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        if (HookRuntime.hook(start, "app_volume/misound/serviceStart") { chain ->
                val host = chain.thisObject as Service
                val intent = chain.args[0] as? Intent
                val target = controllerField.get(host)
                if (target != null && intent?.getBooleanExtra(AppVolumeBridge.DATA_ONLY, false) == true) {
                    MiSoundCardHooks.prepareOpen(Reflect.readField(target, "o") as? View,
                        NativeVolumePanelStyle.read(intent))
                }
                val requested = ready && intent?.getBooleanExtra(AppVolumeBridge.DATA_ONLY, false) == true &&
                    supported()
                val previous = dataOnly.get()
                dataOnly.set(requested)
                try {
                    val result = chain.proceed()
                    if (ready) Reflect.attempt { register(host) }
                    result
                } finally { if (previous == null) dataOnly.remove() else dataOnly.set(previous) }
            }) count++
        val destroy = type.getDeclaredMethod("onDestroy")
        if (HookRuntime.hookAfter(destroy, "app_volume/misound/serviceDestroy") { chain, original ->
                if (service.get() === chain.thisObject) {
                    receiver?.let { listener -> Reflect.attempt { (chain.thisObject as Service).applicationContext.unregisterReceiver(listener) } }
                    receiver = null
                    service.clear()
                    backend.clear()
                }
                original
            }) count++
        if (HookRuntime.hook(controller.getDeclaredMethod("B"), "app_volume/misound/dataOnly") { chain ->
                // Service screen/settings/mute checks still run. Do not create a second window.
                if (ready && (dataOnly.get() == true || backend.active())) null else chain.proceed()
            }) count++
        if (HookRuntime.hook(controller.getDeclaredMethod("t"), "app_volume/misound/dataOnlyUpdate") { chain ->
                if (ready && dataOnly.get() == true) null else chain.proceed()
            }) count++
        val addWindow = controller.getDeclaredMethod("C", View::class.java, WindowManager.LayoutParams::class.java)
        if (HookRuntime.hook(addWindow, "app_volume/misound/hideBall") { chain ->
                val target = chain.thisObject!!
                if (AppVolumeBridgePolicy.hideNativeBall(ready, supported(),
                        chain.args[0] === ball.get(target))) null else chain.proceed()
            }) count++
        // The private window helper can be inlined into z(); service calls can inline B()/t().
        val deoptimized = HookRuntime.deoptimize(controller.getDeclaredMethod("z"), "app_volume/misound/ballCaller") and
            HookRuntime.deoptimize(start, "app_volume/misound/startCaller")
        // 卡片样式是独立于数据桥的第二组 hook；之前只安装了这里的 5 个服务 hook，
        // MiSoundCardHooks 虽然存在但从未接入，因此原生卡片必然始终保留。
        val cardCount = runCatching { MiSoundCardHooks.install(loader, controller) }
            .onFailure { ModuleLog.error("app_volume card hooks unavailable; native card retained", it) }
            .getOrDefault(0)
        ModuleLog.info("app_volume/card_hooks=$cardCount")
        ready = count == 5 && deoptimized
        ModuleLog.info("app_volume structural validation: data_hooks=$count/5 deoptimized=$deoptimized ready=$ready")
        return count + cardCount
    }
}
