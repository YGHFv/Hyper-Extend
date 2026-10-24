/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import android.os.Bundle
import io.github.YGHFv.HyperExtend.core.MilinkSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime

/**
 * 放宽设备互联侧的全部应用流转判断。
 *
 * `MiuiSynergySdk` 的两个查询会先调用静态 `isMirrorNotAlive`，未存活时直接返回 false。
 * 这里后处理真正的 `CallMethod#doCall` 响应，只把已完成远端调用后的 `enable` 字段改为 true；
 * 查询未通过存活门禁时根本不会执行到这里，因此绝不伪造远端可用性。
 * 设备能力 Bundle 与本地副屏应用白名单则分别返回 true，使已连接设备能进入流转路径。
 *
 * 完整功能还需要 `com.xiaomi.mirror` 宿主的服务端白名单、RelayApplication 和流转消息
 * 处理；本功能只覆盖当前 `com.milink.service` 宿主中已取证的设备互联侧。
 */
internal object MilinkSendAppUnlock {
    fun install(loader: ClassLoader): Int {
        var installed = 0
        val callMethodTarget = MilinkTargets.sendAppCallMethodTarget
        val callMethod = MilinkTargets.resolve(loader, callMethodTarget) ?: run {
            warnMissing(callMethodTarget)
            return 0
        }
        if (HookRuntime.hookAfter(callMethod, label(callMethodTarget)) { chain, original ->
                val query = chain.args.getOrNull(1) as? String
                val bundle = original as? Bundle
                if (isSendAppQuery(query) && bundle != null) {
                    bundle.putBoolean("enable", true)
                }
                original
            }) {
            installed++
        }

        for (target in MilinkTargets.sendAppAdditionalTargets) {
            val method = MilinkTargets.resolve(loader, target) ?: run {
                warnMissing(target)
                continue
            }
            if (target == MilinkTargets.subScreenWhitelistTarget) {
                if (HookRuntime.hook(method, label(target)) { chain ->
                        val packageName = chain.args.getOrNull(0) as? String
                        if (hasPackageName(packageName)) true else chain.proceed()
                    }) {
                    installed++
                }
            } else if (HookRuntime.hookReturning(method, label(target), true)) {
                installed++
            }
        }
        return installed
    }

    internal fun isSendAppQuery(query: String?): Boolean =
        query == "isSupportSendApp" || query == "isSupportSendAppToPhone"

    internal fun hasPackageName(packageName: String?): Boolean = !packageName.isNullOrBlank()

    private fun warnMissing(target: MilinkTarget) {
        ModuleLog.warn(
            "${MilinkSettings.UNLOCK_SEND_APP}: target missing " +
                "${target.owner}#${target.name}",
        )
    }

    private fun label(target: MilinkTarget): String =
        "${MilinkSettings.UNLOCK_SEND_APP}/${target.owner.substringAfterLast('.')}#${target.name}"
}
