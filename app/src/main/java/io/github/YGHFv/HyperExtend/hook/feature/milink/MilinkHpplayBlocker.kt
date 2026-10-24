/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

import android.os.Environment
import io.github.YGHFv.HyperExtend.core.MilinkSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.Reflect

/**
 * 阻止乐播 SDK 在共享存储建立设备互联目录。
 *
 * `ContextPath#initDirs` 在 `222bba`、`222c0e`、`222d86` 三处调用私有 `makeDir`。
 * 其中共享存储分支位于 `222d02` 之后；命中归属目录时直接跳过建目录，
 * 不删除已有文件，也不改变 SDK 的私有缓存与数据目录。
 */
internal object MilinkHpplayBlocker {
    fun install(loader: ClassLoader): Int {
        val method = MilinkTargets.resolve(loader, MilinkTargets.hpplayDirectoryTarget)
        if (method == null) {
            ModuleLog.warn("${MilinkSettings.BLOCK_HPPLAY}: ContextPath#makeDir missing")
            return 0
        }
        val externalRoot = Reflect.attempt {
            Environment.getExternalStorageDirectory().absolutePath
        } ?: return 0
        val label = "${MilinkSettings.BLOCK_HPPLAY}/ContextPath#makeDir"
        return if (HookRuntime.hook(method, label) { chain ->
                val path = chain.args.getOrNull(1) as? String
                if (MilinkHpplayPolicy.shouldBlock(externalRoot, path)) {
                    ModuleLog.info("${MilinkSettings.BLOCK_HPPLAY}: shared-storage directory creation skipped")
                    null
                } else {
                    chain.proceed()
                }
            }) 1 else 0
    }
}
