/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.mishare

import io.github.YGHFv.HyperExtend.core.MiShareSettings
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime

/**
 * 阻止互传「临时开启」到点自动关闭。
 *
 * 只跳过硬私有定时器建立方法 [MiShareAutoTurnOffTargets]：定时器从此不再被创建，10 分钟后
 * 的关停（`d()`→`j()`→`MiShareService#o0`）与随之而来的提示都不会发生。**不伪造任何状态** ——
 * 用户自己关掉互传走的是另一条路（`c()` 撤销定时器），完全不受影响。
 *
 * 刻意**不迁移**上游的另外两个 hook：`Context#getString(int)` 替换与通用 Toast 抑制。
 * 那两个都是全进程级别的共享入口，命中范围远大于本功能，与「只改互传自身这一件事」的作用域原则冲突。
 * 代价是：宿主在建立定时器时的那句「%d 分钟后自动关闭」提示仍会照原样出现（见 requirement 说明）。
 */
internal object MiShareAutoTurnOffGuard {
    private const val LABEL = MiShareSettings.DISABLE_AUTO_OFF

    fun install(loader: ClassLoader): Int {
        val arm = MiShareAutoTurnOffTargets.locate({ Class.forName(it, false, loader) })
        if (arm == null) {
            ModuleLog.warn("$LABEL: auto-close timer target missing or ambiguous; skipped")
            return 0
        }
        // `i()` 只被同类里的 `f()` 调用，release 构建可能已把它内联进调用者 ——
        // 把整个类拉回解释执行，内联副本才会跟着失效（与接收端守卫的理由相同）。
        val owner = arm.declaringClass
        if (owner.declaredMethods.any { !HookRuntime.deoptimize(it, "$LABEL/${it.name}") }) return 0
        val label = "$LABEL/${owner.simpleName}#${arm.name}"
        return if (HookRuntime.hookReturning(arm, label, null)) 1 else 0
    }
}
