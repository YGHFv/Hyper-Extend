/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import io.github.YGHFv.HyperExtend.core.MilinkSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime

/**
 * 解锁设备互联的相机设备白名单。
 *
 * 相机云控的 `RulesConfig#isAuthorised` 会先调用 `kp.a#a(Context)` 判断当前设备是否在允许列表；
 * 该判断为假时会在应用黑名单、授权规则、API 与 targetSdk 检查之前直接返回。这里只放宽这一道
 * 设备门禁，不接管整条授权链，因此后续针对具体应用的限制仍然生效。
 */
internal object MilinkAllowCameraDevices {
    private const val LABEL = MilinkSettings.ALLOW_CAMERA_DEVICES

    fun install(loader: ClassLoader): Int {
        val target = MilinkTargets.cameraDeviceTarget
        val method = MilinkTargets.resolve(loader, target) ?: run {
            ModuleLog.warn("$LABEL: target missing ${target.owner}#${target.name}")
            return 0
        }
        val label = "$LABEL/${target.owner}#${target.name}"
        return if (HookRuntime.hookReturning(method, label, true)) 1 else 0
    }
}
