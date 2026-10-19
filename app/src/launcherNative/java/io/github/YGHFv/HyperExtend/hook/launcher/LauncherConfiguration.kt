/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import android.content.SharedPreferences
import androidx.annotation.Keep
import io.github.YGHFv.HyperExtend.core.defaultEnabledOf
import io.github.YGHFv.HyperExtend.core.defaultSliderValueOf

/** 只能由后续经过核验的加载适配器提供，不接受来自偏好设置的身份声明。 */
internal data class LauncherProcessEvidence(
    val pid: Int = 0,
    val uid: Int = -1,
    val packageUid: Int = -1,
    val packageName: String = "",
    val processName: String = "",
    val versionCode: Long = 0,
    val postSpecializationConfirmed: Boolean = false,
    val spawner: Boolean = true,
) {
    fun mayReadConfiguration(): Boolean = pid > 0 && uid >= 10000 && uid == packageUid &&
        packageName == LauncherHostContract.PACKAGE && processName == packageName &&
        postSpecializationConfirmed && !spawner
}

internal data class LauncherConfigurationPacket(
    val pid: Int,
    val revision: Long,
    val issuedMs: Long,
    val expiresMs: Long,
    val features: Int,
    val countLimit: Int,
) {
    fun toWire(): LongArray = longArrayOf(1, pid.toLong(), revision, issuedMs, expiresMs,
        features.toLong(), countLimit.toLong())
}

@Keep
internal object LauncherConfiguration {
    const val PROTOCOL = "launcher_native_protocol"
    const val MASTER = "launcher_native_shortcuts_enabled"
    const val COUNT = "home_other_shortcut_remove_restrictions"
    const val SHARE = "home_other_allow_share_apk"
    const val FREEFORM = "home_other_freeform_shortcut_menu"
    const val TASKS = "home_other_tasks_shortcut_menu"
    const val COUNT_LIMIT = "launcher_native_shortcut_count_limit"
    const val LEASE_MS = 30000L
    val COUNT_LIMIT_DEFAULT: Int
        get() = defaultSliderValueOf(COUNT_LIMIT) ?: 5

    /** 获取整个快照一次；不使用会把类型错误或失联掩盖成默认值的逐键访问器。 */
    fun prepareFromPreferences(evidence: LauncherProcessEvidence, preferences: SharedPreferences?,
        revision: Long, nowElapsedMs: Long): Result<LauncherConfigurationPacket> =
        prepare(evidence, { preferences?.all?.toMap() }, revision, nowElapsedMs)

    fun prepare(evidence: LauncherProcessEvidence, read: () -> Map<String, *>?,
        revision: Long, nowElapsedMs: Long): Result<LauncherConfigurationPacket> = runCatching {
        // 父进程、非桌面或来源不明时，连偏好快照都不能预先取得。
        check(evidence.mayReadConfiguration()) { "桌面子进程身份尚未核验" }
        require(revision in 1..0xffffffffL) { "配置发送序号无效" }
        require(nowElapsedMs in 0..Long.MAX_VALUE - LEASE_MS) { "单调时钟无效" }
        val values = checkNotNull(read()) { "配置快照不可用" }
        require(values[PROTOCOL] is Int && values[PROTOCOL] == 1) { "配置协议缺失或不受支持" }
        fun flag(key: String): Boolean {
            if (!values.containsKey(key)) return defaultEnabledOf(key)
            return values[key] as? Boolean ?: error("配置布尔类型错误：$key")
        }
        val requested = listOf(COUNT, SHARE, FREEFORM, TASKS).foldIndexed(0) { bit, mask, key ->
            if (flag(key)) mask or (1 shl bit) else mask
        }
        val limit = if (values.containsKey(COUNT_LIMIT)) values[COUNT_LIMIT] as? Int
            ?: error("菜单上限类型错误") else COUNT_LIMIT_DEFAULT
        require(limit in 5..20) { "菜单上限越界" }
        LauncherConfigurationPacket(evidence.pid, revision, nowElapsedMs, nowElapsedMs + LEASE_MS,
            if (flag(MASTER)) requested else 0, limit)
    }
}

internal data class LauncherConfigurationDecision(val blockers: Int) {
    // 原生端仍固定返回后三项阻断；此结果仅供预检，不是后续安装可复用的授权令牌。
    val configurationValid: Boolean get() = blockers and 0x700 == 0x700 && blockers and 0x720.inv() == 0
    val allFeaturesOff: Boolean get() = blockers and 0x20 != 0
    val hostUnverified: Boolean get() = blockers and 0x100 != 0
    val safetyUnverified: Boolean get() = blockers and 0x200 != 0
    val backendUnverified: Boolean get() = blockers and 0x400 != 0
}
