/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object FiveGTilePolicy {
    const val SPEC = "hyperextend_5g"
    const val LABEL = "5G"

    /**
     * 样式选择落点。取值与归一化规则在 `core/FiveGTileSettings` 里（纯函数，单元测试覆盖），
     * 这里只做转发，免得 hook 侧再抄一份字面量。
     */
    const val STYLE = io.github.YGHFv.HyperExtend.core.FiveGTileSettings.STYLE

    fun style(raw: String): String = io.github.YGHFv.HyperExtend.core.FiveGTileSettings.normalize(raw)

    fun usesNativeDetail(raw: String): Boolean = io.github.YGHFv.HyperExtend.core.FiveGTileSettings.usesNativeDetail(raw)

    fun iconAsset(raw: String): String = io.github.YGHFv.HyperExtend.core.FiveGTileSettings.iconAsset(raw)

    /**
     * 样式档与「独立磁贴是否可用」的关系。
     *
     * 选了「内置移动网络磁贴」时**不再需要**独立磁贴工厂：那条路径往原生详情页注入，
     * 由宿主自己的磁贴渲染。反过来，只要独立磁贴可用就把 spec 放进候选列表，
     * 让用户能手动添加。
     */
    fun includeInCandidates(style: String, factoryReady: Boolean): Boolean =
        factoryReady && !usesNativeDetail(style)

    fun canToggle(enabled: Boolean, blocked: Boolean, owner: Boolean, locked: Boolean,
        restricted: Boolean, airplane: Boolean, slot: Int, simReady: Boolean): Boolean =
        enabled && !blocked && owner && !locked && !restricted && !airplane && slot in 0..1 && simReady

    data class Selection(val subscription: Int, val slot: Int, val on: Boolean)

    // Never redirect a delayed click to a newly selected/replaced SIM or reverse a newer change.
    fun sameSelection(expected: Selection, actual: Selection?, ageMillis: Long): Boolean =
        expected.subscription >= 0 && expected.slot in 0..1 && expected == actual && ageMillis in 0..2000

    fun settingsTarget(packageName: String?, className: String?, systemApp: Boolean,
        applicationEnabled: Boolean, activityEnabled: Boolean, exported: Boolean, permissionGranted: Boolean) =
        packageName == FiveGSettingsTargets.PACKAGE && className == FiveGSettingsTargets.ACTIVITY &&
            systemApp && applicationEnabled && activityEnabled && exported && permissionGranted
}
