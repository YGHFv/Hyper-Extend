/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.ContentResolver
import android.content.Context
import android.os.UserManager
import io.github.YGHFv.HyperExtend.core.FlashlightStrengthSettings
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StatusIconHost
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手电筒亮度控制的两档实现（见 [FlashlightStrengthSettings]）。
 *
 * ## 档一：开启时限制上限（首版行为，保留）
 *
 * 只在本机 `$ExternalSyntheticLambda3.run` 里、且是 `classId == 0` 那次**开灯**、
 * 原生最大强度正好 100、机主用户、且未被强制关闭 / 低电量关闭 / 手电可用时，
 * 把该次开灯随后那一次 `Settings.Global.getFloat(mi_flashlight_strength)` 的返回值压低。
 * 原生的超级手电 50% 策略先跑，我们只降不升。
 *
 * ## 档二：亮度条实时控制（上游那条路）
 *
 * 手电筒开着时，在 `MiuiBrightnessController.onChanged` 入口把 `mExternalChange` 临时置真，
 * 让这一次拖动**既不写屏幕亮度也不排队写**；随后用宿主自己的
 * `BrightnessUtils.convertGammaToLinearFloat` 把滑块值换算成强度，调
 * `CameraManager.turnOnTorchWithStrengthLevel`。
 *
 * ## 为什么用 `onChanged` 而不是像上游那样 hook 两条写亮度 runnable
 *
 * `onChanged` 的 `if (mExternalChange) return;` 是**两条写路径（v1 策略协程、v2 runnable）
 * 的共同上游**，且位置在构造异步写之前。hook 它一处，等于同时覆盖上游分开 hook 的那两条，
 * 不会出现「v1 机型漏 hook、v2 机型才生效」这种只在部分设备复现的 bug。
 *
 * ## 线程与失败处理
 *
 * `onChanged` 在显示线程；`turnOnTorchWithStrengthLevel` 是普通 Binder 调用，可在原线程完成。
 * 任何一步前置检查失败（控制器没抓到、相机 ID 为空、强度算不出、状态不满足）都**在写任何字段
 * 之前**返回，这一次拖动就照原生走；真正的写入用 `try/finally` 包住，异常也一定会把
 * `mExternalChange` 还原 —— 不会出现「拖动把屏幕亮度的写路径永久关掉」这种状态。
 */
internal object FlashlightStrengthHooks {
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val t = FlashlightStrengthTargets
        val methods = t.methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = t.fields.associate { (owner, name, type) -> name to StatusIconHost.field(loader, owner, name, type) }
        // 只 deoptimize 真正会挂 hook 的方法（`hook`/`hookAfter` 内部本来就会做）。
        // `caller` 与 `strategy` 只是版本证据锚点，从不被 hook：对「只调用、不拦截」的
        // 方法做 deoptimize 只会平白扩大安装失败面（框架方法尤其可能拒绝 deoptimize）。
        // `methods.values.all { resolve(...) }` 已经在上面的 associateWith 里完成了版本闸门。
        val ready = AtomicBoolean(false)
        val scope = NativeCallScope<ContentResolver>()
        // 手电筒控制器实例。只有唯一实例时才用于实时控制（多实例=多用户切换中，宁可不接管）。
        val controllers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun enabled() = ready.get() && !SafeModeRuntime.blocked && settings.isOn(FlashlightStrengthSettings.FEATURE)
        fun live() = FlashlightStrengthSettings.isLive(settings.string(FlashlightStrengthSettings.MODE))
        val read = HookRuntime.hookAfter(methods.getValue(t.read), "flashlight_strength/scoped-read") { chain, result ->
            if (enabled() && scope.value != null && chain.args[0] === scope.value && chain.args[1] == t.KEY &&
                FlashlightStrengthPolicy.directCaller(Thread.currentThread().stackTrace.map { it.className to it.methodName }))
                FlashlightStrengthPolicy.cap(result as Float, settings.number(FlashlightStrengthSettings.PERCENT, 100))
            else result
        }
        val run = HookRuntime.hook(methods.getValue(t.run), "flashlight_strength/native-enable") { chain ->
            val task = chain.thisObject
            val controller = task?.let { runCatching { fields.getValue("f\$0").get(it) }.getOrNull() }
            // 顺手登记控制器：开灯 runnable 上拿着控制器实例，且每台设备只有一个，正好做唯一性判据。
            if (controller != null) synchronized(controllers) { controllers.add(controller) }
            // 只有「开启时限制上限」这一档走这里；实时控制档不碰开灯瞬间的读值。
            val resolver = if (enabled() && !live() && task != null && controller != null) runCatching {
                val eligible = FlashlightStrengthPolicy.accepts(fields.getValue("\$r8\$classId").getInt(task),
                    fields.getValue("f\$1").getBoolean(task), fields.getValue("mFlashlightMaxStrength").getInt(controller),
                    fields.getValue("mCurrentUserId").getInt(controller), fields.getValue("mForceOff").getBoolean(controller),
                    fields.getValue("mBatteryOff").getBoolean(controller), fields.getValue("mTorchAvailable").getBoolean(controller))
                val context = fields.getValue("mContext").get(controller) as Context
                if (eligible) context.contentResolver else null
            }.getOrNull() else null
            // Native strategy runs first and may force 50%. Only reduce its subsequent read, never raise it.
            scope.within(resolver) { chain.proceed() }
        }
        val liveCount = if (read && run) {
            Live.install(loader, settings, controllers, ::enabled, ::live)
        } else 0
        ready.set(read && run)
        return if (ready.get()) 2 + liveCount else 0
    }

    /**
     * 档二的安装与运行。**独立解析、独立计数**：实时控制需要的相机方法在本机取证齐全，
     * 但万一某个宿主版本缺一条，也只应让档二不生效，不能连带把档一一起拖下水。
     */
    private object Live {
        fun install(
            loader: ClassLoader, settings: HookSettings, controllers: MutableSet<Any>,
            enabled: () -> Boolean, live: () -> Boolean,
        ): Int {
            val t = FlashlightStrengthTargets
            val resolved = t.liveMethods.map { NotificationHooks.resolve(loader, it) ?: return 0 }
            // 档二只 hook `onChanged` 一处；`convertGammaToLinearFloat` 与
            // `turnOnTorchWithStrengthLevel` 是**调用**目标，不是拦截目标。对它们 deoptimize
            // 不改变任何行为，却会因框架方法拒绝 deoptimize 而把整档一起判成不可用，
            // 所以只让 `hook(onChanged, ...)` 自己去 deoptimize。
            val onChanged = resolved.first { it.name == t.onChanged.name }
            val gammaToLinear = resolved.first { it.name == t.gammaToLinear.name }
            val turnOnTorch = resolved.first { it.name == t.turnOnTorch.name }
            val brightness = runCatching {
                t.brightnessFields.associate { (type, name, _) -> name to onChanged.declaringClass.field(name, type) }
            }.getOrElse { return 0 }
            val torch = runCatching {
                val owner = Class.forName(t.CONTROLLER, false, loader)
                t.torchFields.associate { (type, name, _) -> name to owner.field(name, type) }
            }.getOrElse { return 0 }
            val externalField = brightness.getValue("mExternalChange")
            val minField = brightness.getValue("mMinimumBacklight")
            val maxField = brightness.getValue("mMaximumBacklight")
            val contextField = torch.getValue("mContext")
            val cameraIdField = torch.getValue("mCameraId")
            val managerField = torch.getValue("mCameraManager")
            val enabledField = torch.getValue("mFlashlightEnabled")

            val changed = HookRuntime.hook(onChanged, "flashlight_strength/live-slider") { chain ->
                val target = chain.thisObject ?: return@hook chain.proceed()
                val controller = synchronized(controllers) { controllers.singleOrNull() }
                    ?: return@hook chain.proceed()
                // 全部是「读」：任何一条不满足都在写 mExternalChange 之前返回，原生分支照旧。
                val plan = runCatching {
                    if (chain.args.getOrNull(1) != true) return@runCatching null   // 非用户拖动
                    val value = chain.args.getOrNull(3) as? Int ?: return@runCatching null
                    val context = contextField.get(controller) as? Context ?: return@runCatching null
                    val cameraId = cameraIdField.get(controller) as? String ?: return@runCatching null
                    if (cameraId.isBlank()) return@runCatching null
                    val manager = managerField.get(controller) ?: return@runCatching null
                    val on = enabledField.getBoolean(controller)
                    val owner = runCatching {
                        val user = context.getSystemService(UserManager::class.java)
                        user?.isSystemUser == true && !user.isManagedProfile
                    }.getOrDefault(false)
                    if (!FlashlightStrengthPolicy.hijack(enabled(), live(), on, owner,
                            SafeModeRuntime.blocked)) return@runCatching null
                    val limit = settings.number(FlashlightStrengthSettings.PERCENT, 100, 1, 100)
                    val strength = FlashlightStrengthPolicy.strength(value, minField.getFloat(target), maxField.getFloat(target),
                        limit) { input, lo, hi -> gammaToLinear.invoke(null, input, lo, hi) as Float }
                        ?: return@runCatching null
                    Snapshot(target, manager, turnOnTorch, cameraId, strength)
                }.getOrNull() ?: return@hook chain.proceed()

                // 分流：把这一次拖动从「写屏幕亮度」改成「写手电筒强度」。
                val previous = externalField.getBoolean(plan.brightness)
                externalField.setBoolean(plan.brightness, true)
                try {
                    // 先落强度再返回：onChanged 原体会读到 mExternalChange=true 直接返回，不再写亮度。
                    runCatching { plan.turnOn.invoke(plan.manager, plan.cameraId, plan.strength) }
                } finally {
                    runCatching { externalField.setBoolean(plan.brightness, previous) }
                }
                null
            }
            return if (changed) 1 else 0
        }
    }

    /** 一次已被判定为「该接管」的拖动：亮度控制器、相机句柄、强度。 */
    private data class Snapshot(
        /** 亮度控制器实例：`mExternalChange` 写在它身上。 */
        val brightness: Any,
        val manager: Any,
        val turnOn: Method,
        val cameraId: String,
        val strength: Int,
    )

    private fun Class<*>.field(name: String, type: String): Field = getDeclaredField(name).apply {
        require(this.type.name == type); isAccessible = true
    }
}
