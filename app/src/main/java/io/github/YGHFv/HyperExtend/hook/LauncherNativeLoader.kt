/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 触发 LSPosed 原生模块协议的实际加载。
 *
 * native_init.list 只会向 LSPosed 登记库名，不会自动 dlopen；在存在 ART 的目标进程中
 * 必须由模块代码主动加载库，LSPosed 的 do_dlopen 监视才会调用 native_init。Rust
 * 桌面本身没有 ART，因此它仍由 HyperOS Runtime 的原生加载链负责。
 * 普通构建没有桌面/system 作用域，也没有这份原生库，因此不会进入这条路径。
 */
internal object LauncherNativeLoader {
    private const val LIBRARY = "HyperExtendLauncherNative"
    private const val LIBRARY_FILE = "libHyperExtendLauncherNative.so"
    private const val MODULE_PACKAGE = "io.github.YGHFv.HyperExtend"
    private const val LAUNCHER = "com.miui.home"
    private val attempted = AtomicBoolean(false)

    fun loadIfTarget(processName: String, systemServer: Boolean, record: (String) -> Unit = {}) {
        if (!BuildConfig.LAUNCHER_NATIVE_PROTOTYPE) return
        // 不把原生库主动加载到 system_server。system_server 只承载 Java 端点；桌面
        // 原生库必须与 HyperCeiler 一样只在桌面目标进程注册。
        if (systemServer || !isLauncherProcess(processName)) return
        if (!attempted.compareAndSet(false, true)) return
        record("launcher native load begin: process=$processName")
        try {
            // 优先走参考项目的标准路径，让 LSPosed 维护的模块命名空间解析库。
            System.loadLibrary(LIBRARY)
            record("launcher native load returned: library=$LIBRARY source=namespace")
            return
        } catch (first: Throwable) {
            record("launcher native namespace load failed: library=$LIBRARY " +
                "error=${first.javaClass.simpleName}: ${first.message.orEmpty().take(240)}")
            val directory = moduleNativeLibraryDir()
            if (directory.isNullOrEmpty()) {
                record("launcher native absolute path unavailable; load abandoned")
                return
            }
            runCatching {
                // 某些重装/热重载场景下命名空间仍指向旧 APK，按参考项目回退到已安装绝对路径。
                System.load("$directory/$LIBRARY_FILE")
                record("launcher native load returned: library=$LIBRARY source=absolute path=$directory")
            }.onFailure {
                record("launcher native absolute load failed: library=$LIBRARY path=$directory " +
                    "error=${it.javaClass.simpleName}: ${it.message.orEmpty().take(240)}")
            }
        }
    }

    private fun moduleNativeLibraryDir(): String? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val application = activityThread.getDeclaredMethod("currentApplication").invoke(null)
        val packageManager = application?.javaClass?.getMethod("getPackageManager")?.invoke(application)
        val info = packageManager?.javaClass
            ?.getMethod("getApplicationInfo", String::class.java, Int::class.javaPrimitiveType)
            ?.invoke(packageManager, MODULE_PACKAGE, 0)
        info?.javaClass?.getField("nativeLibraryDir")?.get(info) as? String
    }.getOrNull()

    private fun isLauncherProcess(processName: String): Boolean =
        processName == LAUNCHER || processName.startsWith("$LAUNCHER:")
}
