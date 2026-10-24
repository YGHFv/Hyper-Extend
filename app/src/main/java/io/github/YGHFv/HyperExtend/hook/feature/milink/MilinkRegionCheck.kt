/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import io.github.YGHFv.HyperExtend.core.MilinkSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime

/**
 * 关闭电脑管家剪贴板同步的地区拦截。
 *
 * `UniversalClipDataReceiver` 在 `397d26` 调用剪贴板侧
 * `LyraUtil#isSameRegionWithLocal`；文件服务侧保留同一组判断。两处都固定为同一地区，
 * 但不改动设备鉴权、连接建立或实际传输方法。
 */
internal object MilinkRegionCheck {
    const val REGION = "cn"

    fun install(loader: ClassLoader): Int {
        var installed = 0
        for (target in MilinkTargets.regionTargets) {
            val method = MilinkTargets.resolve(loader, target)
            if (method == null) {
                ModuleLog.warn("${MilinkSettings.REGION_CHECK}: target missing ${target.owner}#${target.name}")
                continue
            }
            val replacement = replacementFor(target.name) ?: continue
            val label = "${MilinkSettings.REGION_CHECK}/${target.owner.substringAfterLast('.')}#${target.name}"
            if (HookRuntime.hookReturning(method, label, replacement)) installed++
        }
        return installed
    }

    internal fun replacementFor(methodName: String): Any? = when (methodName) {
        "isSameRegionWithLocal" -> true
        "getDeviceRegion" -> REGION
        else -> null
    }
}
