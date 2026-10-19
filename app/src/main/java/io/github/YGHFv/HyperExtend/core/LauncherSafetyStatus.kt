/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import io.github.YGHFv.HyperExtend.hook.BootFuseState

internal enum class LauncherSafetyState {
    /** 从未建立过启动记录；首次正常启用会自动建立，不是熔断。 */
    ABSENT,

    /** 状态完整且没有锁存。 */
    HEALTHY,

    /** 状态完整且明确锁存，需要走原生恢复事务。 */
    BLOCKED,

    /** 存在未完成事务；不能自动清理或伪装成健康。 */
    PENDING,

    /** 路径、格式或状态内容异常；不能自动清理或伪装成健康。 */
    DAMAGED,

    /** root 命令失败或权限不足；既不能证明健康，也不能证明熔断。 */
    UNKNOWN,
}

internal enum class LauncherManualRelease {
    /** 没有锁存，可以直接撤销模块自己的停用标记。 */
    ALLOWED,

    /** 状态完整且明确锁存，必须先走原生恢复事务。 */
    NATIVE_RECOVERY_REQUIRED,

    /** 残留或损坏状态需要人工处理，不能自动覆盖。 */
    BLOCKED_BY_RESIDUE,
}

internal data class LauncherSafetyStatus(
    val status: LauncherSafetyState,
    val state: BootFuseState? = null,
    val reason: String? = null,
    val incidentId: String? = null,
) {
    val blocked: Boolean get() = status == LauncherSafetyState.BLOCKED
    val needsManualAttention: Boolean
        get() = status == LauncherSafetyState.PENDING || status == LauncherSafetyState.DAMAGED
}

/** 原生桌面没有 Java 上报线程；模块侧只读其实际状态，不能把未知状态等同于熔断。 */
internal object LauncherSafetyStatusReader {
    fun read(userId: Int): LauncherSafetyStatus {
        require(userId >= 0)
        val directory = "/data/user/$userId/com.miui.home/files"
        val state = "$directory/hyperextend_launcher_bootguard"
        val pending = "$state.pending"
        val command = "if [ -L '$state' ] || [ -L '$pending' ]; then echo invalid; " +
            "elif [ -e '$pending' ]; then echo pending; " +
            "elif [ -f '$state' ]; then echo state; head -c 129 '$state'; " +
            "elif [ -d '$directory' ]; then echo absent; else echo unavailable; fi"
        return decode(RootAccess.exec(command, 10, globalMountNamespace = true))
    }

    internal fun decode(raw: String?): LauncherSafetyStatus {
        if (raw == null || raw.length > 160) {
            return LauncherSafetyStatus(
                LauncherSafetyState.UNKNOWN,
                reason = "无法读取桌面原生保护状态；这不等于已熔断，请检查 root 授权。",
            )
        }
        val text = raw.replace("\r\n", "\n").trim()
        if (text == "absent") {
            return LauncherSafetyStatus(
                LauncherSafetyState.ABSENT,
                reason = "尚无桌面启动记录，首次正常启动会自动建立。",
            )
        }
        if (text == "pending") {
            return LauncherSafetyStatus(
                LauncherSafetyState.PENDING,
                reason = "桌面保护存在未完成事务；不会自动清理异常残留，请人工处理。",
            )
        }
        if (text == "unavailable") {
            return LauncherSafetyStatus(
                LauncherSafetyState.UNKNOWN,
                reason = "桌面保护路径当前不可读；这不等于已熔断，未执行恢复。",
            )
        }
        if (!text.startsWith("state\n")) {
            return LauncherSafetyStatus(
                LauncherSafetyState.DAMAGED,
                reason = "桌面保护路径存在异常符号链接或不合法记录；不会自动清理。",
            )
        }
        val value = text.substringAfter('\n')
        val state = if (value.length <= 128) BootFuseState.decode(value) else null
        if (state == null) {
            return LauncherSafetyStatus(
                LauncherSafetyState.DAMAGED,
                reason = "桌面原生保护状态损坏，不能确认已恢复，也不会自动覆盖。",
            )
        }
        return LauncherSafetyStatus(
            if (state.disabled) LauncherSafetyState.BLOCKED else LauncherSafetyState.HEALTHY,
            state,
            if (state.disabled) {
                "桌面原生启动保护已锁存（连续异常计数 ${state.rapidRestarts}）；关闭此开关会走原生恢复事务并重启桌面。"
            } else {
                "桌面原生保护正常，连续异常计数 ${state.rapidRestarts}。"
            },
            if (state.disabled) "launcher-native-${state.lastStartAt}-${state.lastResetToken}" else null,
        )
    }
}

/** 安全模式开关的纯状态转换，便于离线回归测试。 */
internal object LauncherSafetyPolicy {
    fun masksModule(status: LauncherSafetyStatus?): Boolean = status?.blocked == true

    fun requiresNativeRecovery(status: LauncherSafetyStatus?): Boolean =
        status?.status == LauncherSafetyState.BLOCKED && status.state != null

    fun canReleaseManualDisable(status: LauncherSafetyStatus?): Boolean =
        release(status) == LauncherManualRelease.ALLOWED

    fun release(status: LauncherSafetyStatus?): LauncherManualRelease = when (status?.status) {
        LauncherSafetyState.BLOCKED -> LauncherManualRelease.NATIVE_RECOVERY_REQUIRED
        LauncherSafetyState.PENDING, LauncherSafetyState.DAMAGED -> LauncherManualRelease.BLOCKED_BY_RESIDUE
        LauncherSafetyState.ABSENT, LauncherSafetyState.HEALTHY, LauncherSafetyState.UNKNOWN, null ->
            LauncherManualRelease.ALLOWED
    }

    fun recovered(status: LauncherSafetyStatus?, before: BootFuseState?): Boolean {
        if (status?.status != LauncherSafetyState.HEALTHY) return false
        val observed = status.state ?: return false
        return before == null || observed.lastResetToken > before.lastResetToken
    }
}
