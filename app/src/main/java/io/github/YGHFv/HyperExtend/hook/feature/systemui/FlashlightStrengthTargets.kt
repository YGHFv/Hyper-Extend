/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

/**
 * 手电筒亮度控制要碰的宿主成员。
 *
 * 两部分：
 * 1. [FlashlightStrengthTargets.run] / [FlashlightStrengthTargets.read] —— 原生开灯路径上的
 *    「上限」分支（首版迁移留下，继续生效）；
 * 2. [FlashlightStrengthTargets.onChanged] / [FlashlightStrengthTargets.turnOnTorch] ——
 *    上游「亮度条实时控制」需要的分流点与强度入口。
 *
 * 每一条都来自本机 202602260 的 MT 只读取证（见 `docs/flashlight-live-control-evidence.json`）。
 */
internal object FlashlightStrengthTargets {
    const val CONTROLLER = "com.android.systemui.controlcenter.policy.MiuiFlashlightControllerImpl"
    const val RUNNABLE = "$CONTROLLER\$\$ExternalSyntheticLambda3"
    const val BRIGHTNESS = "com.android.systemui.controlcenter.policy.MiuiBrightnessController"
    const val BRIGHTNESS_UTILS = "com.android.systemui.controlcenter.policy.BrightnessUtils"
    const val CAMERA_MANAGER = "android.hardware.camera2.CameraManager"

    /** 宿主自己的强度偏好键；[CONTROLLER] 开灯时会读它，超级手电强制 50% 也写它。 */
    const val KEY = "mi_flashlight_strength"

    val run = SystemUiMethod(RUNNABLE, "run", "void")
    val caller = SystemUiMethod(CONTROLLER, "setFlashlightInternal", "void", listOf("boolean"))
    val strategy = SystemUiMethod(CONTROLLER, "doSuperFlashlightStrategy", "void")
    val read = SystemUiMethod("android.provider.Settings\$Global", "getFloat", "float",
        listOf("android.content.ContentResolver", "java.lang.String", "float"), isStatic = true)
    val methods = listOf(run, caller, strategy, read)
    val fields = listOf(
        Triple(RUNNABLE, "\$r8\$classId", "int"), Triple(RUNNABLE, "f\$0", CONTROLLER), Triple(RUNNABLE, "f\$1", "boolean"),
        Triple(CONTROLLER, "mContext", "android.content.Context"), Triple(CONTROLLER, "mCurrentUserId", "int"),
        Triple(CONTROLLER, "mFlashlightMaxStrength", "int"), Triple(CONTROLLER, "mForceOff", "boolean"),
        Triple(CONTROLLER, "mBatteryOff", "boolean"), Triple(CONTROLLER, "mTorchAvailable", "boolean"),
    )

    // ---- 实时亮度条控制（上游「默认写入模式」在本机的等价路径）需要的载体 ----

    /**
     * 亮度控制器里「这一次拖动是在改屏幕亮度还是改手电筒」的分流点。
     *
     * `onChanged` 的第一条分支就是 `if (mExternalChange) return;`，位置在
     * `isUserSliding = p2` 之后、构造写亮度 runnable 之前。把它临时置真，这一次拖动就
     * 既不写屏幕亮度、也不排队异步写 —— 与上游把两条写亮度 runnable 置空效果一致，
     * 但不必分别适配 v1 策略协程与 v2 runnable 两条实现，也不会漏掉其中一条。
     */
    val onChanged = SystemUiMethod(BRIGHTNESS, "onChanged", "void",
        listOf("com.android.systemui.plugins.miui.controlcenter.ToggleSliderBase",
            "boolean", "boolean", "int", "boolean"))
    val brightnessFields = listOf(
        Triple(BRIGHTNESS, "mExternalChange", "boolean"),
        Triple(BRIGHTNESS, "mMinimumBacklight", "float"),
        Triple(BRIGHTNESS, "mMaximumBacklight", "float"),
    )

    /** 宿主自己的 gamma→linear 曲线；强度换算复用它，曲线与屏幕亮度、与上游都一致。 */
    val gammaToLinear = SystemUiMethod(BRIGHTNESS_UTILS, "convertGammaToLinearFloat", "float",
        listOf("int", "float", "float"), isStatic = true)

    /** 本机唯一真实存在的强度入口（上游写 /sys 节点，本机没有那些节点）。 */
    val turnOnTorch = SystemUiMethod(CAMERA_MANAGER, "turnOnTorchWithStrengthLevel", "void",
        listOf("java.lang.String", "int"))

    /** 手电筒控制器的相机句柄、相机 ID 与开关状态。 */
    val torchFields = listOf(
        Triple(CONTROLLER, "mCameraManager", CAMERA_MANAGER),
        Triple(CONTROLLER, "mCameraId", "java.lang.String"),
        Triple(CONTROLLER, "mFlashlightEnabled", "boolean"),
    )

    /** 实时控制路径要解析的全部方法（安装期一次性校验，缺任一条就整条不装）。 */
    val liveMethods = listOf(onChanged, gammaToLinear, turnOnTorch)
}

internal object FlashlightStrengthPolicy {
    fun cap(original: Float, percent: Int): Float {
        if (!original.isFinite() || original !in 0.01f..1f || percent !in 1..100) return original
        return minOf(original, percent / 100f)
    }
    fun accepts(classId: Int, turningOn: Boolean, max: Int, user: Int, forceOff: Boolean,
        batteryOff: Boolean, available: Boolean) = classId == 0 && turningOn && max == 100 &&
        user == 0 && !forceOff && !batteryOff && available
    fun directCaller(frames: List<Pair<String, String>>): Boolean = frames.firstOrNull {
        it.first.startsWith("com.android.systemui.controlcenter.policy.")
    } == (FlashlightStrengthTargets.RUNNABLE to "run")

    /**
     * 这一次拖动是否由本功能接管。
     *
     * 全部为真才接管：功能开着、控制方式选了实时（[FlashlightStrengthSettings.isLive]）、
     * 安全模式没把模块停掉、当前是机主用户、宿主版本对得上、**手电筒确实开着**。
     *
     * 「手电筒开着」是安全底线：手电筒没开时接管亮度条，用户会既没灯也调不了屏幕亮度；
     * 状态未知（还没拿到控制器）一律按「没开」处理，不接管。
     */
    fun hijack(enabled: Boolean, live: Boolean, torchOn: Boolean, owner: Boolean, blocked: Boolean): Boolean =
        enabled && live && torchOn && owner && !blocked

    /**
     * 由亮度条拖动值算出手电筒强度（1..100 的整数）。
     *
     * 换算函数由调用方传入宿主自己的 `BrightnessUtils.convertGammaToLinearFloat`，
     * 所以曲线与屏幕亮度一致，也就与上游一致。任何一步拿不到可用值（量程非法、
     * 换算结果非有限、上限越界）都返回 null，由调用方放行原生分支 —— 宁可这一次拖动
     * 照旧改屏幕亮度，也不要给相机一个猜出来的强度。
     */
    fun strength(slider: Int, min: Float, max: Float, limitPercent: Int,
        convert: (Int, Float, Float) -> Float,
    ): Int? {
        if (limitPercent !in 1..100) return null
        if (!min.isFinite() || !max.isFinite() || max <= min) return null
        val linear = runCatching { convert(slider, min, max) }.getOrNull() ?: return null
        if (!linear.isFinite()) return null
        // linear 是 0..1 的归一化亮度；强度上限只降不升（与同卡片上限滑块语义一致）。
        return Math.round(linear.coerceIn(0f, 1f) * 100f).coerceIn(1, limitPercent)
    }
}
