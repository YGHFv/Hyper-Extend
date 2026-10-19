/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import androidx.annotation.Keep

/** 仅原型构建打包；没有静态加载器，也没有接到宿主生命周期或正式功能开关。 */
@Keep
internal object LauncherNativeBridge {
    private external fun nativeStatus(): Int
    private external fun nativeApiVersion(): Long
    private external fun nativeBeginConfiguration(): Boolean
    private external fun nativeCheckConfiguration(words: LongArray): Int
    private external fun nativeQueryConfiguration(): Int
    private external fun nativeChannelStatus(): Int
    private external fun nativeSnapshotRevision(): Long
    private external fun nativeSnapshotFeatures(): Long

    /** 成功仅表示诊断可查询；捕获 JVM 异常不能恢复原生崩溃，也不构成挂载许可。 */
    fun loadForDiagnostics(): Result<DiagnosticStatus> = runCatching {
        val identity = LauncherAndroidIdentity.capture().getOrThrow()
        System.loadLibrary("HyperExtendLauncherNative")
        check(LauncherAndroidIdentity.capture().getOrThrow() == identity) { "原型加载期间宿主身份发生变化" }
        DiagnosticStatus(nativeStatus(), nativeApiVersion())
    }

    /**
     * 只读诊断：向 system_server 同步取一次配置快照。
     *
     * 返回的是固定的阻断位，**0 只表示这一次取到了满足字节契约的配置**，不是安装许可，
     * 也不会触发任何 hook 安装。桌面原生进程里本方法不可用（无 ART）；它只在
     * system_server 或普通 Java 宿主里用于人工核对通道是否贯通。
     */
    fun queryConfigurationOnce(): Result<ConfigurationProbe> = runCatching {
        val blockers = nativeQueryConfiguration()
        ConfigurationProbe(blockers, nativeChannelStatus(), nativeSnapshotRevision(),
            nativeSnapshotFeatures())
    }

    /** 不隐式加载库、不缓存快照；配置正确仍会报告尚未取得的宿主、安全和后端许可。 */
    fun checkConfiguration(packet: LauncherConfigurationPacket): Result<LauncherConfigurationDecision> = runCatching {
        check(nativeBeginConfiguration()) { "本进程配置通道尚未就绪" }
        LauncherConfigurationDecision(nativeCheckConfiguration(packet.toWire()))
    }

    data class DiagnosticStatus(val flags: Int, val observedFrameworkVersion: Long) {
        val jniLoaded: Boolean get() = flags and 2 != 0
        val nativeCallbackSeen: Boolean get() = flags and 4 != 0
        /** 位 0x8 仅表示入口记录缺少可用的 native_init 参数，不是版本不匹配。 */
        val entriesRejected: Boolean get() = flags and 8 != 0
        /** 位 0x800 仅表示本进程拿到了入口结构；版本数字从不参与判定。 */
        val entriesVerified: Boolean get() = flags and 0x800 != 0
        val inheritedState: Boolean get() = flags and 16 != 0
    }

    /**
     * 通道阻断位与最近采纳字段，仅供人工观察，不携带安装授权。
     * JNI 的两个独立 getter 不是跨调用事务；并发刷新时不能把它们组成安装依据。
     * 无 ART 桌面使用 native_init 调度的单次 worker，并通过 HELauncherProbe 输出诊断。
     */
    data class ConfigurationProbe(
        val blockers: Int,
        val lastStatus: Int,
        val snapshotRevision: Long,
        val snapshotFeatures: Long,
    ) {
        val accepted: Boolean get() = blockers == 0
        val noBinder: Boolean get() = blockers and 1 != 0
        val noWindowService: Boolean get() = blockers and 2 != 0
        val transactFailed: Boolean get() = blockers and 4 != 0
        val shortReply: Boolean get() = blockers and 8 != 0
        val snapshotRejected: Boolean get() = blockers and 16 != 0
        val notQueried: Boolean get() = blockers and 32 != 0
        val busy: Boolean get() = blockers and 64 != 0
        val wrongProcess: Boolean get() = blockers and 128 != 0
    }
}
