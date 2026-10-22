/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.github.YGHFv.HyperExtend.hook.feature.system

import android.content.Context
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关闭设备名称违规词校验。
 *
 * Wi-Fi 服务来自独立模块类加载器。先挂 `SystemServiceManager#loadClassFromLoader`，
 * 等它加载 `WifiService` 时取得同一个 ClassLoader，再挂真正的判定入口
 * `com.android.server.wifi.Utils#checkDeviceNameIsIllegalSync`。
 */
internal object SystemFrameworkAntiQues {
    private const val FEATURE = "system_framework_anti_ques"
    private const val WIFI_SERVICE = "com.android.server.wifi.WifiService"
    private const val WIFI_UTILS = "com.android.server.wifi.Utils"
    private const val CHECK_NAME = "checkDeviceNameIsIllegalSync"

    private val wifiHookInstalled = AtomicBoolean(false)

    fun install(loader: ClassLoader, settings: HookSettings) {
        if (!settings.isOn(FEATURE)) return
        val manager = Reflect.loadClass(loader, "com.android.server.SystemServiceManager")
        val target = manager?.let {
            Reflect.findMethod(it, "loadClassFromLoader", String::class.java, ClassLoader::class.java)
        }
        if (target == null) {
            ModuleLog.warn("$FEATURE: SystemServiceManager#loadClassFromLoader not found")
            return
        }
        HookRuntime.hook(target, "$FEATURE/SystemServiceManager#loadClassFromLoader") { chain ->
            val className = chain.args.getOrNull(0) as? String
            val classLoader = chain.args.getOrNull(1) as? ClassLoader
            val result = chain.proceed()
            if (className == WIFI_SERVICE && classLoader != null) hookWifiUtils(classLoader)
            result
        }
    }

    private fun hookWifiUtils(loader: ClassLoader) {
        if (!wifiHookInstalled.compareAndSet(false, true)) return
        val utils = Reflect.attempt { Class.forName(WIFI_UTILS, false, loader) }
        val method = utils?.let {
            Reflect.findMethod(
                it,
                CHECK_NAME,
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                String::class.java,
            )
        }
        if (method == null) {
            wifiHookInstalled.set(false)
            ModuleLog.warn("$FEATURE: $WIFI_UTILS#$CHECK_NAME not available in Wi-Fi loader")
            return
        }
        if (HookRuntime.hookReturning(method, "$FEATURE/$WIFI_UTILS#$CHECK_NAME", false)) {
            ModuleLog.info("$FEATURE: Wi-Fi device-name check disabled")
        } else {
            wifiHookInstalled.set(false)
        }
    }
}
