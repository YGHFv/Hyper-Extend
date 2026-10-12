/* Copyright (C) 2023-2026 HyperCeiler Contributions; Copyright (C) 2026 YGHFv;
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

/**
 * 手电筒亮度控制（上游 `security_flash_light_switch` 的迁移落点）。
 *
 * ## 上游到底做了什么
 *
 * 上游是**「开启手电筒时劫持亮度条，拖动亮度条控制手电筒亮度」**（`NewFlashLight.kt`）：
 *
 * 1. 手电筒开着时，hook 亮度控制器里两条「把滑块值写进屏幕亮度」的 runnable，
 *    让屏幕亮度**不被改动**；
 * 2. 把滑块值按宿主自己的 `BrightnessUtils.convertGammaToLinearFloat` 换算成强度，
 *    再写进手电筒节点。
 *
 * 写节点有四种模式（数组 `security_center_flash_light_value` = 0..3）：
 * - **0 关闭**：整条功能不生效；
 * - **1 默认写入模式**：往存在的节点直接写；
 * - **2 写入模式一**：先写 0 再写目标值（部分驱动需要一次归零）；
 * - **3 写入模式二**：写目标值后点一下 `/sys/class/leds/led:switch_0/brightness` 触发。
 *
 * ## 本机为什么不能照抄
 *
 * 真机取证（见 `docs/flashlight-live-control-evidence.json`）：本机闪光灯节点是
 * `/sys/class/leds/{yellow:flash-0,white:flash-1,amber:flash-2}`，**没有**上游写死的
 * `/sys/class/leds/led:torch_0`、`/sys/class/leds/flashlight` 或
 * `/sys/class/flashlight_core`；而且宿主 `MiuiFlashlightControllerImpl` 根本不写节点，
 * 它走 `CameraManager.turnOnTorchWithStrengthLevel`（强度 0..100 的整数）。
 *
 * 所以本模块**不写节点、不 chmod、不用 root**，强度落点改用宿主自己的公共入口
 * `CameraManager.turnOnTorchWithStrengthLevel`。上游那三种写节点模式（1/2/3）在本机
 * 没有对应物 —— 它们描述的是「怎么写同一个节点」，而本机的问题不是写法而是**没有那个节点**，
 * 因此不伪造三个等价档，只保留上游真正区分开的那一档语义：**亮度条实时控制**。
 *
 * ## 为什么还有「原生开灯上限」这一档
 *
 * 上游的实时控制要求「手电筒先开着」。在系统自己不写节点、只认相机强度的平台上，
 * 还有一条与实时控制互补、且已经被真机取证过的路径：在手电筒**开灯那一刻**用同一条
 * `Settings.Global.mi_flashlight_strength` 读值把本次强度压低。这一档就是首版迁移留下的
 * 「上限」。两档效果不同（一个实时跟随滑块、一个只在开灯时定一次），所以按选项卡共存。
 *
 * ## 权限与安全边界
 *
 * `turnOnTorchWithStrengthLevel` 需要 CAMERA 权限（SystemUI 已有）；亮度条劫持只影响
 * 屏幕亮度写入，不改手势、不改锁屏、不改亮度偏好。退出监听（松手 / 面板收起 / 关闭功能）
 * 就恢复原状，手电筒本身仍由系统手电筒磁贴控制开关。
 */
object FlashlightStrengthSettings {
    const val FEATURE = "flashlight_strength_limit"

    /** 首版迁移留下的强度上限键，保留以免老设置丢失。 */
    const val PERCENT = "$FEATURE.percent"

    const val MODE = "$FEATURE.mode"

    /** 只压低开灯时的原生强度（首版行为）。 */
    const val MODE_CEILING = "1"

    /** 上游那条「亮度条实时控制」。 */
    const val MODE_LIVE = "2"

    /**
     * 两种效果互斥的实现路径。用户不需要同时要两套，且同一张卡片下同时装两段 hook
     * 会让「拖动亮度条到底是改屏幕还是改手电筒」变得无法解释，所以做成多选一。
     */
    val style = HyperChoice(
        MODE, "控制方式",
        listOf(
            ChoiceEntry(MODE_CEILING, "开启时限制上限"),
            ChoiceEntry(MODE_LIVE, "亮度条实时控制"),
        ),
        summary = "「开启时限制上限」只在手电筒开启那一刻压低强度；" +
            "「亮度条实时控制」在手电筒开着时接管控制中心亮度条，拖动即可调手电筒亮度且不改屏幕亮度",
    )

    val config = listOf(style, HyperSlider(PERCENT, "强度上限", 1, 100, 100, unit = "%"))

    /** 归一化：只认两个已登记取值，其余按出厂档（限制上限）。 */
    fun normalize(raw: String): String = when (raw) {
        MODE_CEILING, MODE_LIVE -> raw
        else -> MODE_CEILING
    }

    /** 这一档是否走「亮度条实时控制」。 */
    fun isLive(raw: String): Boolean = normalize(raw) == MODE_LIVE
}
