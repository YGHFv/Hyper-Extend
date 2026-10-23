/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import io.github.YGHFv.HyperExtend.core.defaultEnabledOf
import io.github.YGHFv.HyperExtend.core.defaultSliderValueOf
import java.nio.charset.StandardCharsets

/**
 * 桌面配置通道的**线路契约**（Kotlin 侧）。字段序号、确认码、协议与原生
 * `app/src/main/cpp/launcher/snapshot_wire.h` 必须逐字一致 —— 两边各写一份
 * 字面量是刻意的：原生侧在桌面进程里、Kotlin 侧在 system_server 里，
 * 无法共享同一份代码，只能靠这里与那边的编译期/单元测试同时钉死。
 *
 * 事务码与 HyperCeiler 的 `0x0048434A..0x0048434D` 完全不重叠：两套模块会
 * 挂在同一个 `IWindowManager$Stub.onTransact` 上，码撞了就会互相截获对方的
 * Parcel，读成畸形数据。
 */
internal object LauncherSnapshotWire {
    /** 事务码。与 `launcher/channel.h` 的 kChannelTransaction 必须相等。 */
    const val TRANSACTION = 0x00484541
    const val MAINTENANCE_TRANSACTION = 0x00484542
    const val SHARE_TRANSACTION = 0x00484543
    const val SHARE_QUEUED_ACK = 0x48455332

    /** 回复首字确认码 `"HE41"`。 */
    const val ACK = 0x48453431

    /** 协议版本。与原生侧 kSnapshotProtocol 必须相等。 */
    const val PROTOCOL = 1

    /** 回复字段数：确认码、协议、序号、签发、到期、功能位、上限、已武装、恢复令牌、保留位。 */
    const val WORDS = 10
    const val MAINTENANCE_ACK = 0x48454d31
    const val MAINTENANCE_PROTOCOL = 1
    const val MAINTENANCE_WORDS = 8
    const val SHARE_ACK = 0x48455331
    const val SHARE_REJECTED_ACK = 0x48455330
    const val SHARE_PROTOCOL = 1
    const val SHARE_POINT_PRIMARY = 1
    const val SHARE_POINT_SECONDARY = 2

    /** 快照租期：**整秒**。理由见原生侧 snapshot_wire.h（毫秒会在 25 天后溢出 int32）。 */
    const val LEASE_SECONDS = 30
    const val COUNT_LIMIT_MIN = 5
    const val COUNT_LIMIT_MAX = 20
    val COUNT_LIMIT_DEFAULT: Int
        get() = defaultSliderValueOf(COUNT_LIMIT) ?: COUNT_LIMIT_MIN

    /** 四个菜单开关的功能位；顺序即 bit 序号，必须与 LauncherConfiguration 的取值顺序一致。 */
    const val FEATURE_COUNT = 1 shl 0
    const val FEATURE_SHARE = 1 shl 1
    const val FEATURE_FREEFORM = 1 shl 2
    const val FEATURE_TASKS = 1 shl 3
    /** 禁用应用预加载。它不依赖快捷菜单主开关，走同一份原生属性快照。 */
    const val FEATURE_PRESTART = 1 shl 4
    /** 快捷菜单的四个功能位；预加载位单独计算，不参与主开关门禁。 */
    const val FEATURE_MASK = 0xF

    // 设置键。取自 core/LauncherNativeSettings；集中在这里是为了让「通道读哪些键」
    // 是一处可见的清单，而不是散在事务处理里。
    const val MASTER = "launcher_native_shortcuts_enabled"
    const val COUNT = "home_other_shortcut_remove_restrictions"
    const val SHARE = "home_other_allow_share_apk"
    const val FREEFORM = "home_other_freeform_shortcut_menu"
    const val TASKS = "home_other_tasks_shortcut_menu"
    const val PRESTART = "home_other_disable_prestart"
    const val COUNT_LIMIT = "launcher_native_shortcut_count_limit"

    /** 拒绝应答：确认码 + 协议 + 全 0；序号和上限非法，原生侧先判 kSnapshotMalformed。 */
    fun denied(): IntArray = IntArray(WORDS).also {
        it[0] = ACK
        it[1] = PROTOCOL
    }

    fun deniedMaintenance(): IntArray = IntArray(MAINTENANCE_WORDS).also {
        it[0] = MAINTENANCE_ACK
        it[1] = MAINTENANCE_PROTOCOL
    }
}

/** 从设置里读出的一份完整快照。全部字段都是**已经校验过范围**的值。 */
internal data class LauncherSnapshot(
    val revision: Int,
    val issuedSeconds: Int,
    val features: Int,
    val countLimit: Int,
    val armed: Boolean,
    val resetToken: Int,
) {
    /**
     * 编成线路上的 10 个 int32。
     *
     * 保留位固定 0；`expires` 由签发时间 + 租期算出，两者都是 int32，
     * 因此必须在**相加之前**确认不会溢出：签发时间已经是「当前开机秒数」，
     * 只可能远小于上界，这里再挡一次，避免任何路径把它变成负数。
     */
    fun toWire(): IntArray {
        val expires = issuedSeconds.toLong() + LauncherSnapshotWire.LEASE_SECONDS
        return intArrayOf(
            LauncherSnapshotWire.ACK,
            LauncherSnapshotWire.PROTOCOL,
            revision,
            issuedSeconds,
            expires.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            features,
            countLimit,
            if (armed) 1 else 0,
            resetToken,
            0,
        )
    }
}

/**
 * 把「读到的设置值」收敛成一份普通配置快照，不签发恢复授权。
 *
 * 这里**不是**能读就行：每一位都必须落在原生侧会接受的范围里，否则原生侧
 * 会判 kSnapshotMalformed 或 kSnapshotProtocolMismatch 而整包拒绝。
 * 于是判定集中在这一个纯函数里，事务处理只负责取时间、写回复。
 * 专用恢复授权尚未实现，恢复字段固定为 0；主开关及其他宿主的恢复操作
 * 都不能解除桌面熔断，也不能授权初始化或清理残留状态。
 *
 * @param revision 严格递增的发送序号；调用方保证单调（见 endpoint）
 * @param issuedSeconds 开机单调时钟整秒（SystemClock.elapsedRealtime() / 1000）
 * @param read 按设置键取值；返回 null 表示这个键没被写过，走保守默认
 */
internal fun buildLauncherSnapshot(
    revision: Int,
    issuedSeconds: Int,
    resetToken: Int = 0,
    read: (String) -> Any?,
): LauncherSnapshot? {
    // 时钟或序号本身不可信时直接拒绝应答：给一份畸形快照不如让原生侧超时重试。
    if (revision <= 0 || issuedSeconds <= 0) return null

    fun flag(key: String): Boolean = when (val value = read(key)) {
        is Boolean -> value
        // 与 HookSettings.raw 保持一致：未写入的新子项使用目录默认值，
        // 但错误类型仍按关闭处理，避免把损坏设置误当成启用。
        null -> defaultEnabledOf(key)
        else -> false
    }
    // 类型错误（写成字符串/整数）按「没配」处理，而不是按 true —— 与 HookSettings
    // 的 fail-closed 一致：读不准的开关一律当作关。
    val menuRequested = (if (flag(LauncherSnapshotWire.COUNT)) LauncherSnapshotWire.FEATURE_COUNT else 0) or
        (if (flag(LauncherSnapshotWire.SHARE)) LauncherSnapshotWire.FEATURE_SHARE else 0) or
        (if (flag(LauncherSnapshotWire.FREEFORM)) LauncherSnapshotWire.FEATURE_FREEFORM else 0) or
        (if (flag(LauncherSnapshotWire.TASKS)) LauncherSnapshotWire.FEATURE_TASKS else 0)
    // 快捷菜单主开关关着时，菜单功能位一律为 0：这样「用户从没进过界面」不会
    // 变成「子项全开」；预加载位不经过该主开关，但同样受安全模式门禁约束。
    val safetyDisabled = when (val value = read("safety.launcher.disabled")) {
        null -> false
        is Boolean -> value
        else -> true
    }
    val menuEnabled = flag(LauncherSnapshotWire.MASTER) && !safetyDisabled
    // 预加载与快捷菜单主开关无关：用户只想要「别预加载」时不应被迫打开快捷菜单扩展。
    val prestartEnabled = flag(LauncherSnapshotWire.PRESTART) && !safetyDisabled
    val features = (if (menuEnabled) menuRequested else 0) or
        (if (prestartEnabled) LauncherSnapshotWire.FEATURE_PRESTART else 0)
    val limit = when (val raw = read(LauncherSnapshotWire.COUNT_LIMIT)) {
        is Int -> raw
        // 滑块在设置层存的是十进制字符串（见 core/FeatureCatalog 的 HyperSlider）。
        is String -> raw.toIntOrNull() ?: LauncherSnapshotWire.COUNT_LIMIT_DEFAULT
        else -> LauncherSnapshotWire.COUNT_LIMIT_DEFAULT
    }.coerceIn(LauncherSnapshotWire.COUNT_LIMIT_MIN, LauncherSnapshotWire.COUNT_LIMIT_MAX)

    // 线路上的「已武装」只表示配置主开关，不证明本地状态已持久化或授权恢复。
    val armed = menuEnabled || prestartEnabled || resetToken > 0
    if (resetToken !in 0..Int.MAX_VALUE) return null
    return LauncherSnapshot(revision, issuedSeconds, features, limit, armed, resetToken)
}

/**
 * 调用方身份判定。
 *
 * 与上游 HyperCeiler `HomeLayoutNativeEndpointOS4.verifyLauncherProcess` 同级：
 * uid 必须是非系统应用（>= 10000），且 `/proc/<pid>/cmdline` 逐字以
 * `com.miui.home` 开头、紧跟一个 NUL。**已知弱点**：同一 uid 下的另一个线程
 * 可以伪造这个 pid 通过校验 —— 这是上游既有的、不是本轮引入的；本项目接受它，
 * 因为回复里没有任何可导致代码执行的载荷，最坏情况是别的线程读到一份自己的配置。
 *
 * uid 为 0/1000 这类系统身份会被 `uid < 10000` 直接拒绝：system_server 自己
 * 不应该通过这条通道取桌面配置。
 */
internal fun isLauncherCaller(uid: Int, pid: Int, cmdline: ByteArray?): Boolean {
    if (uid < 10000 || pid <= 0 || cmdline == null) return false
    val expected = LAUNCHER_PACKAGE.toByteArray(StandardCharsets.US_ASCII)
    if (cmdline.size < expected.size + 1) return false
    if (cmdline[expected.size] != 0.toByte()) return false
    for (index in expected.indices) if (cmdline[index] != expected[index]) return false
    return true
}

internal const val LAUNCHER_PACKAGE = "com.miui.home"

/** 只读取一个受限长度的 cmdline；读不到返回 null（调用方据此拒绝）。 */
internal fun readCmdline(pid: Int, limit: Int = 64): ByteArray? = runCatching {
    val file = java.io.File("/proc/$pid/cmdline")
    val bytes = ByteArray(limit)
    java.io.FileInputStream(file).use { input ->
        val count = input.read(bytes)
        if (count <= 0) null else bytes.copyOf(count)
    }
}.getOrNull()
