/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 「旋转锁定保护」。作用域：system_server。
 *
 * 问题：澎湃 OS 上「方向锁定」（控制中心的竖屏锁定，对应
 * Settings.System.ACCELEROMETER_ROTATION == 0）会在**强制停止应用**后被自动关闭
 * —— 调试时按 Stop / adb shell am force-stop / 后台一键清理，锁定状态就丢了。
 *
 * ## 实现策略是「阻止写入」，不是「检测后锁回」
 *
 * 写入者在 services.jar 的 dex 里全部找齐了（当前 ROM 实证，system_server 里引用
 * "accelerometer_rotation" 字符串的类只有两个，写入点收敛为三处）：
 *
 * 1. `DisplayRotation#setUserRotation(IILjava/lang/String;)V`
 *    —— `useDefaultSettingsProvider()` 为 false 的分支**直接** `putIntForUser`；
 * 2. 默认 provider 分支调用
 *    `DeviceStateAutoRotateSettingController#requestAccelerometerRotationSettingChange`，
 *    异步走 `writeInMemoryStateIntoPersistedSetting` 里的 `putIntForUser`（第 3 处）。
 *
 * 也就是说**所有把锁定状态改掉的路径都经过这两个方法**。于是策略很简单：
 *
 * - 盯住 `ActivityManagerService#forceStopPackage*`（所有强制停止的必经点），
 *   记下发生时刻，开一个短窗口；
 * - 窗口期内，上面两个写入方法被拦下 —— 设置值从头到尾就没有变过，
 *   不存在「先丢再锁回」的中间态，也不需要知道是谁、为了什么写；
 * - 窗口期外一切照旧：用户在控制中心/设置里拨开关走的是同两个方法，不受影响。
 *
 * ---------------------------------------------------------------------------
 * ## 两条铁律（2026-09-28 因一次真实的开机循环事故而定下）
 *
 * ### 铁律一：安装必须推迟到系统服务就绪之后
 *
 * `onSystemServerStarting` 发生在 `SystemServer.startBootstrapServices()` **之前** ——
 * 那一刻 `ActivityManagerService` 这个类还**没有被系统加载**。此时对它做
 * `Class.forName` + `declaredMethods`，等于在系统自己初始化它之前把整条依赖链
 * （AMS 的上千个方法签名引用的类型）拉进解析流程，会破坏启动时序。
 * 事故表现：system_server 崩溃 → 重启 → 再崩 → **开机循环**。
 *
 * 所以本类的 [installDeferred] 不在启动回调里直接装 hook，而是挂到
 * `SystemServer#startOtherServices(TimingsTraceAndSlog)V` **之后** —— 那个方法跑完时
 * AMS / WMS 早已完全初始化，随便加载它们都是安全的。
 *
 * ### 铁律二：hook 的返回值必须原样透传
 *
 * `forceStopPackage` 在 AMS 上有多达八个重载，其中 `forceStopPackageLocked(...)`、
 * `forceStopPackageInternalLocked(...)` 返回 **boolean**，其余返回 void。
 * libxposed 的 `intercept` 是**替换**语义：拦截体返回什么，调用方就收到什么。
 * 早先的版本对所有变体统一返回 `null`，于是在返回 boolean 的变体上，ART 拆箱 null
 * 抛 `NullPointerException` —— 而这个异常抛回的是 **AMS 持锁的调用链**，
 * 直接导致 system_server 崩溃。
 *
 * 所以下面每一处拦截都写成 `chain.proceed()` 的**直接透传**（返回值类型永远与目标
 * 方法一致），需要「不发车」时才返回与目标返回类型一致的常量。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.os.SystemClock
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicBoolean

/** 「旋转锁定保护」。作用域：system_server。 */
internal object RotationLockGuard {

    private const val FEATURE = "rotation_lock_fix"

    /** 强制停止之后的拦截窗口（毫秒）。旋转状态迁移在该窗口内必然已发生。 */
    private const val BLOCK_WINDOW_MS = 5000L

    /** 延迟安装的锚点：它执行完时 AMS / WMS 已经全部就绪。 */
    private const val SYSTEM_SERVER = "com.android.server.SystemServer"
    private const val ANCHOR_METHOD = "startOtherServices"

    /** 启动路径只调度一次；热重载路径只安装一次。两条路各自幂等。 */
    private val deferredScheduled = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)

    /**
     * 最近一次强制停止的时刻（`SystemClock.elapsedRealtime()`）。
     * system_server 里所有 force-stop 都串行经过 AMS，volatile 够用；
     * 0 表示本会话还没发生过强制停止。
     */
    @Volatile
    private var lastForceStopAt = 0L

    /**
     * 启动路径的入口：**不立刻装 hook**，只把安装动作挂到系统服务启动完成之后。
     *
     * 见文件头「铁律一」。锚点 `SystemServer` 类在 `onSystemServerStarting` 时刻**已经在执行**
     * （它就是 system_server 的入口类），所以引用它不会触发任何新的类加载 ——
     * 这一点与铁律一针对的 AMS 有本质区别。
     */
    fun installDeferred(loader: ClassLoader, settings: HookSettings) {
        if (!settings.isOn(FEATURE)) return
        if (!deferredScheduled.compareAndSet(false, true)) return

        val server = Reflect.loadClass(loader, SYSTEM_SERVER)
        if (server == null) {
            ModuleLog.warn("$FEATURE: $SYSTEM_SERVER not found — deferred install skipped")
            return
        }
        // 不写死参数类型：`startOtherServices` 的参数在各版本间变过（TimingsTraceAndSlog、
        // 更早是 TimingsTraceLog），而它在 SystemServer 里是唯一同名方法，按名字取即可。
        val anchor = server.declaredMethods.firstOrNull { it.name == ANCHOR_METHOD }
        if (anchor == null) {
            ModuleLog.warn("$FEATURE: $SYSTEM_SERVER#$ANCHOR_METHOD not found — deferred install skipped")
            return
        }

        val ok = HookRuntime.hook(anchor, "$FEATURE/SystemServer#$ANCHOR_METHOD") { chain ->
            // 先让系统把全部服务启动完，之后加载 AMS / WMS 才是安全的。
            val result = chain.proceed()
            runCatching { installHooks(loader) }
                .onFailure { ModuleLog.error("$FEATURE: deferred install failed", it) }
            result
        }
        if (!ok) {
            ModuleLog.warn("$FEATURE: failed to arm deferred install on $ANCHOR_METHOD")
        } else {
            ModuleLog.info("$FEATURE: deferred install armed on $SYSTEM_SERVER#$ANCHOR_METHOD")
        }
    }

    /**
     * 热重载路径的入口：此时系统早就跑起来了（用户是在系统里点的按钮），
     * 直接安装即可 —— 这里加载 AMS 与在 [installDeferred] 的锚点之后加载是同一处境。
     */
    fun installNow(loader: ClassLoader, settings: HookSettings) {
        if (!settings.isOn(FEATURE)) return
        // 幂等由 installHooks 内部保证；这里**不能**先置位，否则会把真正的安装挡在外面。
        runCatching { installHooks(loader) }
            .onFailure { ModuleLog.error("$FEATURE: install failed", it) }
    }

    /** 真正的安装。**只能在系统服务就绪之后调用**（见文件头「铁律一」）。 */
    private fun installHooks(loader: ClassLoader): Int {
        if (!installed.compareAndSet(false, true)) return 0
        var count = 0
        count += installKillWatcher(loader)
        count += installSetUserRotationBlock(loader)
        count += installPersistedWriteBlock(loader)
        ModuleLog.info("$FEATURE: installed $count hook(s) (prevent mode, deferred)")
        return count
    }

    /**
     * 记录强制停止时刻，开启拦截窗口。
     *
     * 拦截体里只做一次 volatile 写，然后**原样透传** `proceed()` 的返回值 ——
     * 这个类的方法返回值有 void 也有 boolean，透传是唯一能同时满足两者的写法
     * （见文件头「铁律二」）。
     */
    private fun installKillWatcher(loader: ClassLoader): Int {
        val ams = Reflect.loadClass(loader, "com.android.server.am.ActivityManagerService")
        if (ams == null) {
            ModuleLog.warn("$FEATURE: ActivityManagerService not found")
            return 0
        }
        val candidates = ams.declaredMethods.filter {
            it.name.startsWith("forceStopPackage") &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == String::class.java
        }
        if (candidates.isEmpty()) {
            ModuleLog.warn("$FEATURE: no forceStopPackage* on ActivityManagerService")
            return 0
        }
        var installedCount = 0
        for (method in candidates) {
            val signature = method.parameterTypes.joinToString(",") { it.simpleName }
            val ok = HookRuntime.hook(
                method,
                "$FEATURE/AMS#${method.name}($signature)",
            ) { chain ->
                lastForceStopAt = SystemClock.elapsedRealtime()
                // 透传：void 变体拿到 null，boolean 变体拿到原值。绝不写死返回值。
                chain.proceed()
            }
            if (ok) installedCount++
        }
        ModuleLog.info("$FEATURE: kill watcher on $installedCount forceStopPackage method(s)")
        return installedCount
    }

    /**
     * 窗口期内，直接写入点被拦（`setUserRotation` 返回 void，所以「不发车」= 返回 null）。
     */
    private fun installSetUserRotationBlock(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(loader, "com.android.server.wm.DisplayRotation")
        if (clazz == null) {
            ModuleLog.warn("$FEATURE: DisplayRotation not found")
            return 0
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setUserRotation" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.java &&
                it.parameterTypes[1] == Int::class.java &&
                it.parameterTypes[2] == String::class.java
        }
        if (method == null) {
            ModuleLog.warn("$FEATURE: DisplayRotation#setUserRotation(IILjava/lang/String) not found")
            return 0
        }
        val ok = HookRuntime.hook(method, "$FEATURE/DisplayRotation#setUserRotation") { chain ->
            if (inWindow() && wouldFlip(chain)) {
                ModuleLog.info(
                    "$FEATURE: blocked setUserRotation(mode=${chain.args[0]}, caller=${chain.args[2]})",
                )
                null
            } else {
                chain.proceed()
            }
        }
        return if (ok) 1 else 0
    }

    /**
     * 窗口期内，控制器的持久化写回被拦。
     *
     * Android 16 AOSP 的对应方法返回 `void`，部分澎湃版本可能改成 `boolean`；
     * 拦截时必须按实际返回类型给值，不能把一种返回值硬套到所有版本。
     */
    private fun installPersistedWriteBlock(loader: ClassLoader): Int {
        val clazz = Reflect.loadClass(
            loader,
            "com.android.server.wm.DeviceStateAutoRotateSettingController",
        )
        if (clazz == null) {
            ModuleLog.warn("$FEATURE: DeviceStateAutoRotateSettingController not found")
            return 0
        }
        var installedCount = 0
        val requestMethod = clazz.declaredMethods.firstOrNull {
            it.name == "requestAccelerometerRotationSettingChange" &&
                it.parameterTypes.size in 2..3 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                (it.parameterTypes.size == 2 || it.parameterTypes[2] == String::class.java)
        }
        if (requestMethod != null) {
            val blockedRequestResult: Any? = when (requestMethod.returnType) {
                Void.TYPE -> null
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaObjectType -> false
                else -> null
            }
            if (requestMethod.returnType == Void.TYPE ||
                requestMethod.returnType == Boolean::class.javaPrimitiveType ||
                requestMethod.returnType == Boolean::class.javaObjectType
            ) {
                val requestHooked = HookRuntime.hook(
                    requestMethod,
                    "$FEATURE/DSAutoRotateCtrl#requestAccelerometerRotationSettingChange",
                ) { chain ->
                    if (inWindow()) {
                        ModuleLog.info(
                            "$FEATURE: blocked queued auto-rotate change " +
                                "caller=${chain.args.getOrNull(2)} (kill window)",
                        )
                        blockedRequestResult
                    } else {
                        chain.proceed()
                    }
                }
                if (requestHooked) installedCount++
            } else {
                ModuleLog.warn(
                    "$FEATURE: unsupported requestAccelerometerRotationSettingChange return type " +
                        requestMethod.returnType.name,
                )
            }
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "writeInMemoryStateIntoPersistedSetting" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            ModuleLog.warn("$FEATURE: writeInMemoryStateIntoPersistedSetting not found")
            return 0
        }
        val blockedResult: Any? = when (method.returnType) {
            Void.TYPE -> null
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaObjectType -> false
            else -> {
                ModuleLog.warn(
                    "$FEATURE: unsupported writeInMemoryStateIntoPersistedSetting return type " +
                        method.returnType.name,
                )
                return 0
            }
        }
        val ok = HookRuntime.hook(method, "$FEATURE/DSAutoRotateCtrl#writePersisted") { chain ->
            if (inWindow()) {
                ModuleLog.info("$FEATURE: blocked persisted auto-rotate write (kill window)")
                blockedResult
            } else {
                chain.proceed()
            }
        }
        if (ok) installedCount++
        return installedCount
    }

    private fun inWindow(): Boolean =
        lastForceStopAt != 0L && SystemClock.elapsedRealtime() - lastForceStopAt < BLOCK_WINDOW_MS

    /**
     * 这次 `setUserRotation` 会不会把锁定状态改掉。
     *
     * dex 实证的换算：`userRotationMode == 1`（锁定）→ `accelerometer_rotation = 0`；
     * 否则（自由/自动）→ `1`。当前值不一致才拦 —— 原样重放（no-op）放行不碍事。
     */
    private fun wouldFlip(chain: io.github.libxposed.api.XposedInterface.Chain): Boolean {
        val mode = chain.args[0] as? Int ?: return false
        val target = if (mode == 1) 0 else 1
        val current = lockedState() ?: return false
        return target != current
    }

    /**
     * 读当前锁定状态；读不到返回 null（此时放行原调用，绝不瞎猜）。
     *
     * 用 `Settings.System.getIntForUser` 的反射调用而不是 public 的 `getInt`：
     * 前者才带 user 维度，而 system_server 里的调用身份与当前用户可能不一致。
     */
    private fun lockedState(): Int? {
        val context = systemContext() ?: return null
        return Reflect.attempt {
            val method = android.provider.Settings.System::class.java.getDeclaredMethod(
                "getIntForUser",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.java,
            )
            method.isAccessible = true
            method.invoke(
                null,
                context.contentResolver,
                KEY_ACCELEROMETER,
                userCurrent(),
            ) as? Int
        }
    }

    /** `UserHandle.USER_CURRENT` 是 @hide 常量，反射取；失败兜底 -2（该值自 API 1 起未变）。 */
    private fun userCurrent(): Int = runCatching {
        android.os.UserHandle::class.java.getField("USER_CURRENT").getInt(null)
    }.getOrDefault(-2)

    private const val KEY_ACCELEROMETER = "accelerometer_rotation"

    @Volatile
    private var contextCache: android.content.Context? = null

    /**
     * system_server 里拿 Context 的唯一稳妥姿势：`ActivityThread.currentActivityThread()
     * .getSystemContext()`。两者都是隐藏 API，只能反射；结果缓存 —— 这个调用不便宜。
     */
    private fun systemContext(): android.content.Context? {
        contextCache?.let { return it }
        synchronized(this) {
            contextCache?.let { return it }
            val resolved = Reflect.attempt {
                val threadClass = Class.forName("android.app.ActivityThread")
                val thread = threadClass.getMethod("currentActivityThread").invoke(null)
                threadClass.getMethod("getSystemContext").invoke(thread) as android.content.Context
            }
            if (resolved == null) {
                ModuleLog.warn("$FEATURE: system context unavailable — setUserRotation guard inert")
            }
            contextCache = resolved
            return resolved
        }
    }
}
