/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.CarrierTextSettings

/**
 * 运营商文本的改写规则（纯函数）。
 *
 * 拆出来的理由与目录里其它策略相同：这是**有分支的判断**（取值是否可信、第几张卡、
 * 设备名读不到时怎么办），直接写在拦截体里就只能靠真机验证。
 *
 * ## 在控制中心这一侧，文本剥离主要是防御
 *
 * 见 `CarrierTextTargets.measure` 的取证：控制中心的监听者收到的是**逐卡槽**的
 * `mCurrentCarrier[slot]`，其中不含 `" | "`（合并文本只走那个双参空实现的分发）。
 * 所以 `1` 档真正起作用的是分隔符视图那一处，这里的 `strip` 在本 ROM 上是无害的空操作。
 * 留着它的理由是**跨版本**：旧宿主与锁屏那条分发链给到的就是合并文本，
 * 而这段代码按卡槽与数量收口，两种形态都能正确处理，不会因为换 ROM 版本而改错东西。
 *
 * ## 「返回 null」是本模块的统一语义
 *
 * null 一律表示**不要动宿主的那份文本**，而不是「把它改成空」。这两件事差别很大：
 * 改成空会让宿主自己走进「无服务」分支去重算可见性与无障碍标签；返回 null 则是
 * 让原生值原样流过去。所以任何「读不到 / 不认得」的情况都必须返回 null。
 */
internal object CarrierTextPolicy {
    /** 宿主一侧最多两张实体卡：`mPhoneCount` 与 `mCurrentCarrier` 的长度都由它决定。 */
    private const val MAX_CARRIER_COUNT = 2
    private const val MAX_SLOT = 1

    /**
     * @param mode [CarrierTextSettings.modeOf] 归一化后的取值
     * @param carrierCount 宿主传来的「可用运营商数量」；越界视为不可信
     * @param slot 宿主传来的物理卡槽；越界视为不可信
     * @param deviceName 设备名称；空白视为读不到
     * @return 要替换成的文本，或 null 表示保持原生
     */
    fun transform(
        mode: String,
        carrierCount: Int?,
        slot: Int?,
        text: String?,
        deviceName: String?,
    ): String? {
        if (carrierCount == null || slot == null) return null
        if (carrierCount !in 0..MAX_CARRIER_COUNT || slot !in 0..MAX_SLOT) return null
        return when (CarrierTextSettings.modeOf(mode)) {
            CarrierTextSettings.MODE_SEPARATOR -> if (text.isNullOrEmpty()) null else strip(text)
            CarrierTextSettings.MODE_DEVICE_NAME -> when {
                // 宿主报「一张卡都没有」时不显示设备名：那时候原生本来就是空的，
                // 硬塞一个名字等于凭空造出一个原生不显示的文本。
                carrierCount == 0 -> null
                deviceName.isNullOrBlank() -> null
                // 固定只在 0 号卡显示。不能按 carrierCount 决定显示在哪一格：
                // 生产方对两张卡用的是同一个 count 去重，跟着数量走的格位会漏掉第二次回调。
                slot == 0 -> deviceName
                else -> ""
            }
            else -> null
        }
    }

    /**
     * 剥掉双卡运营商名之间的分隔符。
     *
     * 两种空格都要处理：宿主在 `updateCarrierText` 里拼的是 `" | "`，但上游同时兼容
     * `"  |  "`（不同语言/版本下宽度不同）。只替换一种会在另一种 ROM 上「看起来没生效」。
     */
    fun strip(text: String): String = text.replace("  |  ", "").replace(" | ", "")
}
