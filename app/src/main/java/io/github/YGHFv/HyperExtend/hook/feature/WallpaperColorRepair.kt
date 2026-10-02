/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 *
 * ---------------------------------------------------------------------------
 * 功能来源：HyperWallpaperMonet（PengDingkang，Apache-2.0）。
 *
 * Apache-2.0 允许在保留版权声明的前提下复用实现，因此本文件的修复算法是对上游
 * `ThemeOverlayColorFix` / `ReflectionUtils` 的等价 Kotlin 重写：常量的含义、
 * 字段名、判定顺序与调用时机保持一致，只是把上游的自定义日志接口换成了本模块的
 * [ModuleLog]，并按本模块的反射工具（[Reflect]）收敛了取值路径。
 */

package io.github.YGHFv.HyperExtend.hook.feature

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.lang.reflect.Field
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 澎湃 OS 壁纸取色（Monet 动态主题色）修复。
 *
 * ## 症状与成因
 *
 * 用户看到的是「动态取色一直是默认蓝，换了壁纸也不变」。上游定位到的成因是
 * `ThemeOverlayController` 的**状态不完整**：它要么没收到壁纸颜色事件、
 * 要么收到了但没能把颜色落到覆盖层字段（`mColorScheme` / `mNeutralOverlay` /
 * `mSecondaryOverlay` / `mDynamicOverlay`）上，于是后续所有取色都退回默认色板。
 *
 * ## 修复思路
 *
 * 三段，对应上游的三个入口：
 *
 * 1. **启动后补偿**（[onControllerStarted]）：控制器 `start()` 返回后，直接从
 *    `WallpaperManager` 读当前壁纸颜色补写进 `mCurrentColors`，必要时强制 `reevaluateSystemTheme(true)`。
 *    这一步解决「开机后一直是蓝的」。
 * 2. **延迟复检**：壁纸服务可能晚于 SystemUI 就绪，所以 1.5s / 5s 再各补一次。
 * 3. **事件修正**（[onColorsChanged]）：真的收到壁纸颜色事件时，若覆盖层状态仍不完整，
 *    就再强制重算一次。这一步解决「换壁纸后不变」。
 *
 * ## 为什么要防重入，而且还要限流
 *
 * `reevaluateSystemTheme` 自己又会触发 `onColorsChanged`（以及可能的 `reevaluate` 重入），
 * 不设重入闸就会形成「修正 → 重算 → 修正」的自激循环，表现为 SystemUI 持续高 CPU。
 * 上游用 ThreadLocal 布尔量挡同一线程内的重入；这里改成**全局**布尔量，因为重算经常把
 * 后续工作派到别的线程，ThreadLocal 在那种情况下等于没挡。
 *
 * 更关键的一层是**频率**：`reevaluateSystemTheme(true)` 的参数为 true 表示「连覆盖层
 * 一起重建」，是几十毫秒级的重活，而它跑在 SystemUI 的主线程上。一旦判据在某个 ROM 上
 * 失效（字段名对不上 → 读回来永远是 null → 永远「不完整」），每次重算都会被掰成重建，
 * 用户感受到的就是「用了这个模块之后整机发卡」。所以除了重入闸，还加了最小间隔与总次数
 * 上限（见 [MIN_FORCE_INTERVAL_MS]、[MAX_FORCE_ATTEMPTS] 与 [needsForcedReevaluate]）。
 * 这三道闸共同保证：同一份壁纸颜色的修复有明确预算，不会无限重建覆盖层。
 */
internal class WallpaperColorRepair {

    private companion object {
        /** `WallpaperManager.FLAG_SYSTEM`。公开 API 里的常量，写死比反射便宜且不会变。 */
        const val FLAG_SYSTEM = 1

        /** `WallpaperManager.FLAG_LOCK`。 */
        const val FLAG_LOCK = 2

        /** 用户 id 读不到时的哨兵值（`Integer.MIN_VALUE`，与真实 uid 不可能碰撞）。 */
        const val USER_UNKNOWN = Int.MIN_VALUE

        /** 延迟复检的时间点。间隔取 1.5s / 5s：前者覆盖 SystemUI 自身初始化，后者覆盖壁纸服务就绪。 */
        val RETRY_DELAYS_MS = longArrayOf(1_500L, 5_000L)

        /**
         * 两次强制重算之间至少隔这么久。
         *
         * `reevaluateSystemTheme(true)` 会让控制器**连覆盖层一起重建** —— 一次是几十毫秒级的
         * 资源解析与主题重建，而它跑在 SystemUI 的主线程上。壁纸服务在开机阶段会连发几次
         * 颜色事件，不给间隔设限就等于让 SystemUI 在这段时间里反复重建主题，
         * 用户看到的是「开完机之后状态栏一直一顿一顿的」。
         */
        const val MIN_FORCE_INTERVAL_MS = 1_500L

        /**
         * 强制重算的总次数上限。
         *
         * 见 [needsForcedReevaluate] 的注释：判据在某些 ROM 上会**恒为真**，于是每一次
         * `reevaluateSystemTheme` 都被掰成「连覆盖层一起重建」。这里给一个上限，
         * 同一用户的同一份壁纸颜色超过预算就停止强制；实际换色后重新计数，
         * 最小间隔不清零，避免用完启动预算后永远无法处理后续壁纸。
         */
        const val MAX_FORCE_ATTEMPTS = 6

        /** 判据覆盖的四个覆盖层字段名。 */
        val OVERLAY_FIELDS = listOf(
            "mColorScheme",
            "mNeutralOverlay",
            "mSecondaryOverlay",
            "mAccentOverlay",
            "mDynamicOverlay",
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 正在执行强制重算。
     *
     * 用全局布尔量而不是 ThreadLocal：`reevaluateSystemTheme` 可能把后续工作派到别的线程，
     * ThreadLocal 在那种情况下形同不存在，于是「修正 → 重算 → 再修正」会跨线程自激。
     * 这里要的就是**整个进程同一时刻只允许一次**。
     */
    private val forcing = AtomicBoolean(false)

    /** 强制重算的次数与时间戳。见 [MAX_FORCE_ATTEMPTS] 与 [MIN_FORCE_INTERVAL_MS]。 */
    private var forceAttempts = 0
    private var lastForceAt = 0L
    private var lastRepairColors: WallpaperColors? = null
    private var lastRepairUserId = USER_UNKNOWN
    private var pendingReevaluate: Runnable? = null

    /**
     * 预算已用完（被 [forceReevaluate] 置位）。
     *
     * 用 volatile 而不是靠 `@Synchronized` 兜：它会被 [beforeReevaluate] 在**没有拿锁**的
     * 情况下读（那个函数跑在 `reevaluateSystemTheme` 的调用路径上，不能为了读一个布尔量去抢锁）。
     */
    @Volatile
    private var budgetExhausted = false

    /** 判据字段的解析结果，按类缓存一次（见 [fieldsFor]）。 */
    private class JudgeFields(val mainColor: Field?, val overlays: List<Pair<String, Field>>)

    private var judgeClass: Class<*>? = null
    private var judgeFields: JudgeFields? = null

    /** 判据在本机彻底失效（字段一个都不叫那个名字）。见 [needsForcedReevaluate]。 */
    @Volatile
    private var judgeBroken = false

    /**
     * 主题控制器启动完成：立刻补偿一次，再按需排两次延迟复检。
     *
     * @param scheduleRetry 是否排延迟复检。对应界面上「延迟复检」那一项：
     *   关掉它只影响补写次数，不影响立刻补偿那一次。
     */
    fun onControllerStarted(controller: Any?, scheduleRetry: Boolean) {
        if (controller == null) return
        ModuleLog.info("monet: ThemeOverlayController started; repairing wallpaper colors")
        refresh(controller, "start")
        if (!scheduleRetry) return
        for (delay in RETRY_DELAYS_MS) {
            mainHandler.postDelayed({ refresh(controller, "start+${delay}ms") }, delay)
        }
    }

    /**
     * 壁纸颜色事件到达。
     *
     * @param colors 事件携带的颜色，可能为 null（壁纸被清空/服务还没准备好）——
     *   这时退回「直接读当前壁纸」的路径，而不是放弃。
     */
    fun onColorsChanged(controller: Any?, colors: WallpaperColors?, which: Int, userId: Int) {
        if (controller == null || which and FLAG_SYSTEM == 0) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onColorsChanged(controller, colors, which, userId) }
            return
        }
        if (colors == null) {
            refresh(controller, "onColorsChanged(null)")
            return
        }
        if (injectColors(controller, colors, userId, "event")) {
            forceReevaluate(controller, "onColorsChanged")
        }
    }

    /**
     * `reevaluateSystemTheme` 即将执行。
     *
     * @return true 表示调用方应把参数强制成 `true`（这次重算需要连覆盖层一起重建）
     */
    fun beforeReevaluate(controller: Any?): Boolean {
        if (controller == null) return false
        // 三个前置条件，任一成立都直接放行原样调用 —— 每一次「强制 true」都是一次
        // 覆盖层重建，代价落在 SystemUI 的主线程上，不能凭习惯去要。
        if (forcing.get()) return false
        if (judgeBroken) return false
        return try {
            val userId = currentUserId(controller, USER_UNKNOWN)
            if (userId == USER_UNKNOWN) return false
            val existing = currentColorsForUser(controller, userId)
            if (existing != null) observeColors(existing, userId)
            if (budgetExhausted) return false
            if (existing != null) {
                // 颜色已在，但覆盖层字段还不全 —— 不补这一步，重算会用空覆盖层跑一遍，
                // 结果还是默认色板。
                return needsForcedReevaluate(controller) && requestForceBudget(controller, "beforeReevaluate")
            }
            injectCurrentWallpaperColors(controller, userId, "beforeReevaluate") &&
                requestForceBudget(controller, "beforeReevaluate")
        } catch (t: Throwable) {
            ModuleLog.error("monet: prepare beforeReevaluate failed", t)
            false
        }
    }

    /** 读当前壁纸颜色并补写；成功补写（或有待重建的覆盖层）时强制重算一次。 */
    private fun refresh(controller: Any, reason: String) {
        try {
            val userId = currentUserId(controller, USER_UNKNOWN)
            if (userId == USER_UNKNOWN) return
            if (injectCurrentWallpaperColors(controller, userId, reason)) {
                forceReevaluate(controller, reason)
            }
        } catch (t: Throwable) {
            ModuleLog.error("monet: refresh failed ($reason)", t)
        }
    }

    /** 从 WallpaperManager 取当前壁纸颜色（先系统壁纸，再锁屏壁纸），补写进控制器。 */
    private fun injectCurrentWallpaperColors(controller: Any, userId: Int, reason: String): Boolean {
        val manager = Reflect.readField(controller, "mWallpaperManager") as? WallpaperManager
        if (manager == null) {
            ModuleLog.warn("monet: mWallpaperManager missing ($reason)")
            return false
        }
        val systemColors = Reflect.attempt { manager.getWallpaperColors(FLAG_SYSTEM) }
        val colors = systemColors ?: Reflect.attempt { manager.getWallpaperColors(FLAG_LOCK) }
        if (colors == null) {
            ModuleLog.info("monet: no wallpaper colors available ($reason)")
            return false
        }
        return injectColors(controller, colors, userId, reason)
    }

    /**
     * 把 [colors] 写进控制器的 `mCurrentColors`。
     *
     * @return true 表示「需要来一次强制重算」（要么刚写进去了新颜色，要么颜色没变但覆盖层不完整）
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectColors(
        controller: Any,
        colors: WallpaperColors,
        eventUserId: Int,
        reason: String,
    ): Boolean {
        val effectiveUserId = currentUserId(controller, eventUserId)
        // 多用户/工作资料场景：只处理当前用户的事件，否则会把另一个用户的壁纸色写进来。
        if (eventUserId != USER_UNKNOWN && effectiveUserId != eventUserId) {
            ModuleLog.info("monet: ignore colors for non-current user ($eventUserId != $effectiveUserId)")
            return false
        }
        if (effectiveUserId == USER_UNKNOWN) return false
        val currentColors =
            Reflect.readField(controller, "mCurrentColors") as? SparseArray<WallpaperColors>
        if (currentColors == null) {
            ModuleLog.warn("monet: mCurrentColors missing")
            return false
        }
        observeColors(colors, effectiveUserId)
        val previous = currentColors.get(effectiveUserId)
        if (colors == previous) {
            // 颜色没变但覆盖层还是不完整：说明上一次重算没跑完，这里返回 true 让调用方再推一次。
            return needsForcedReevaluate(controller)
        }
        currentColors.put(effectiveUserId, colors)
        // mAcceptColorEvents=false 时控制器会把自己的后续事件全丢掉，补完颜色必须打开它，
        // 否则「补了这一次、之后换壁纸又不动了」。
        Reflect.writeField(controller, "mAcceptColorEvents", true)
        ModuleLog.info(
            "monet: injected wallpaper colors user=$effectiveUserId ($reason) ${summarize(colors)}",
        )
        return true
    }

    private fun currentColorsForUser(controller: Any, userId: Int): WallpaperColors? =
        Reflect.attempt {
            @Suppress("UNCHECKED_CAST")
            (Reflect.readField(controller, "mCurrentColors") as? SparseArray<WallpaperColors>)
                ?.get(userId)
        }

    /**
     * 覆盖层是否处于「不完整」状态。
     *
     * 判据与上游一致：`mMainWallpaperColor == 0`（主色还是未初始化），或四个覆盖层字段
     * 里任意一个仍为 null。这两条覆盖了「颜色写进去了但重算没发生」的全部情况。
     *
     * ## 「字段不存在」不等于「字段为 null」
     *
     * 按名字读回来是 null 有两种完全不同的原因：字段存在但还没写值，或者**这个 ROM 上
     * 字段根本不叫这个名字**（R8 改名、版本差异）。把后者也当成「不完整」，判据就会恒为真，
     * 于是每一次 `reevaluateSystemTheme` 都被掰成「连覆盖层一起重建」——
     * 那是**持续烧 CPU 的自激回路**，也就是「用了这个模块之后整机发卡」最典型的来源。
     * 所以先确认字段**在类上确实存在**，再去看它的值；一个都不存在就停用判据。
     *
     * ## 字段解析结果按类缓存
     *
     * 这个方法会被每一次 `reevaluateSystemTheme` 与每一次颜色事件调到。每次都重新按名字
     * 沿继承链反射一遍字段表，在这个调用频率下不是小开销，所以第一次算完就存下来。
     */
    private fun needsForcedReevaluate(controller: Any): Boolean {
        if (judgeBroken) return false
        val fields = fieldsFor(controller.javaClass)
        if (fields == null) {
            judgeBroken = true
            ModuleLog.warn(
                "monet: none of the completeness fields exist on " +
                    "${controller.javaClass.simpleName} — judge disabled, will not force reevaluate",
            )
            return false
        }
        val mainColor = fields.mainColor
        if (mainColor != null && readIntOrNull(mainColor, controller) == 0) return true
        return fields.overlays.any { (_, field) -> readOrNull(field, controller) == null }
    }

    /** 解析（并缓存）判据字段；字段一个都不存在时返回 null。 */
    @Synchronized
    private fun fieldsFor(clazz: Class<*>): JudgeFields? {
        if (judgeClass === clazz) return judgeFields
        val mainColor = Reflect.attempt { Reflect.findField(clazz, "mMainWallpaperColor") }
        val overlays = OVERLAY_FIELDS.mapNotNull { name ->
            Reflect.attempt { Reflect.findField(clazz, name) }?.let { name to it }
        }
        judgeClass = clazz
        judgeFields = if (mainColor == null && overlays.isEmpty()) null else JudgeFields(mainColor, overlays)
        return judgeFields
    }

    private fun readOrNull(field: Field, target: Any): Any? =
        Reflect.attempt {
            field.isAccessible = true
            field.get(target)
        }

    private fun readIntOrNull(field: Field, target: Any): Int? =
        Reflect.attempt {
            field.isAccessible = true
            field.getInt(target)
        }

    private fun currentUserId(controller: Any, fallback: Int): Int {
        val tracker = Reflect.readField(controller, "mUserTracker") ?: return fallback
        val userId = Reflect.attempt {
            Reflect.findMethods(tracker.javaClass, "getUserId").firstOrNull()
                ?.also { it.isAccessible = true }
                ?.invoke(tracker) as? Int
        }
        return userId ?: fallback
    }

    /** `reevaluateSystemTheme(boolean)` 方法句柄的缓存。它按名字扫一遍所有方法，不该每次重算都扫。 */
    private var reevaluateMethod: java.lang.reflect.Method? = null
    private var reevaluateMethodResolved = false

    /**
     * 调 `reevaluateSystemTheme(true)`。
     *
     * 三重限制，每一重都对应一类真实的故障（见 [MIN_FORCE_INTERVAL_MS] / [MAX_FORCE_ATTEMPTS]）：
     * 全局重入闸、最小间隔、总次数上限。任何一重不满足都只是**不做这次强制**，
     * 原来那次 `reevaluateSystemTheme` 照常按系统自己的参数执行 —— 这永远是安全的兜底。
     */
    private fun forceReevaluate(controller: Any, reason: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { forceReevaluate(controller, reason) }
            return
        }
        if (!forcing.compareAndSet(false, true)) return
        try {
            val method = reevaluateMethodFor(controller.javaClass)
            if (method == null) {
                ModuleLog.warn("monet: reevaluateSystemTheme(boolean) not found")
                return
            }
            if (!requestForceBudget(controller, reason)) return
            method.isAccessible = true
            method.invoke(controller, true)
            ModuleLog.info("monet: forced reevaluateSystemTheme(true) ($reason, #$forceAttempts)")
        } catch (t: Throwable) {
            ModuleLog.error("monet: force reevaluate failed ($reason)", t)
        } finally {
            forcing.set(false)
        }
    }

    @Synchronized
    private fun observeColors(colors: WallpaperColors, userId: Int) {
        if (colors == lastRepairColors && userId == lastRepairUserId) return
        lastRepairColors = colors
        lastRepairUserId = userId
        forceAttempts = 0
        budgetExhausted = false
    }

    @Synchronized
    private fun acquireForceBudget(): Boolean {
        if (budgetExhausted) return false
        if (forceAttempts >= MAX_FORCE_ATTEMPTS) {
            budgetExhausted = true
            ModuleLog.warn("monet: force budget exhausted ($MAX_FORCE_ATTEMPTS) for current wallpaper colors")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (lastForceAt != 0L && now - lastForceAt < MIN_FORCE_INTERVAL_MS) return false
        lastForceAt = now
        forceAttempts += 1
        return true
    }

    @Synchronized
    private fun requestForceBudget(controller: Any, reason: String): Boolean {
        if (acquireForceBudget()) {
            pendingReevaluate?.let(mainHandler::removeCallbacks)
            pendingReevaluate = null
            return true
        }
        if (budgetExhausted || pendingReevaluate != null) return false
        val remaining = (lastForceAt + MIN_FORCE_INTERVAL_MS - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        val pending = Runnable {
            synchronized(this) { pendingReevaluate = null }
            forceReevaluate(controller, "$reason+throttled")
        }
        pendingReevaluate = pending
        if (!mainHandler.postDelayed(pending, remaining)) pendingReevaluate = null
        return false
    }

    @Synchronized
    private fun reevaluateMethodFor(clazz: Class<*>): java.lang.reflect.Method? {
        if (reevaluateMethodResolved) return reevaluateMethod
        reevaluateMethod = Reflect.findMethods(clazz, "reevaluateSystemTheme")
            .firstOrNull {
                it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
        reevaluateMethodResolved = true
        return reevaluateMethod
    }

    private fun summarize(colors: WallpaperColors?): String {
        if (colors == null) return "null"
        return try {
            "primary=${hex(colors.primaryColor)} secondary=${hex(colors.secondaryColor)} " +
                "tertiary=${hex(colors.tertiaryColor)}"
        } catch (t: Throwable) {
            "unreadable"
        }
    }

    private fun hex(color: Color?): String =
        if (color == null) "null" else String.format(Locale.US, "#%08x", color.toArgb())
}
