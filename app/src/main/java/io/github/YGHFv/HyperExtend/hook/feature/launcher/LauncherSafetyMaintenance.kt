/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import android.os.SystemClock
import android.os.Process
import io.github.YGHFv.HyperExtend.config.HyperSettings
import io.github.YGHFv.HyperExtend.core.FrameworkBridge
import io.github.YGHFv.HyperExtend.core.PlannedRestartRequest
import io.github.YGHFv.HyperExtend.core.PlannedRestarts
import io.github.YGHFv.HyperExtend.core.RootAccess
import java.security.SecureRandom

/**
 * 桌面安全状态的人工维护请求。
 *
 * 这不是普通功能开关，也不把令牌投影到配置快照；它只在用户明确点击维护动作时，
 * 向框架托管的远端设置写入一条短时、一次性的请求。system_server 端点消费后立即移除，
 * 桌面原生端还会重新核对 PID、UID、操作和租期。没有这条请求时，维护事务不会被调用。
 */
internal object LauncherSafetyMaintenance {
    internal data class Pending(val key: String, val stored: Any, val request: String)
    private const val REQUEST_KEY = "launcher_native_safety_maintenance_request"
    private const val RECOVERY_SERIAL_KEY = "launcher_native_recovery_serial"
    private const val VERSION = 1
    private const val ARM_ABSENT = 1
    private const val REARM_KNOWN = 2
    private const val MAX_REQUEST_AGE_SECONDS = 30L
    internal const val PLANNED_RESTART_TOKEN_TAG = 0x40000000
    private val random = SecureRandom()

    /** 只开放首次初始化；残留恢复仍需后续独立验收，不从普通界面放行。 */
    fun requestArmAbsent(): Result<Unit> = runCatching {
        val preferences = checkNotNull(FrameworkBridge.remotePreferences(HyperSettings.GROUP)) {
            "框架远端设置不可用"
        }
        val issued = SystemClock.elapsedRealtime() / 1000L
        check(issued in 1..Int.MAX_VALUE.toLong()) { "开机时钟不可用" }
        val token = random.nextInt(PLANNED_RESTART_TOKEN_TAG - 1) + 1
        val request = "$VERSION|$ARM_ABSENT|$issued|$token"
        check(preferences.edit().putString(REQUEST_KEY, request).commit()) { "维护请求写入失败" }
    }

    /** 必须先确认请求写入，再结束桌面；恢复保护与普通重启是两种明确不同的用户动作。 */
    fun requestForRestart(recover: Boolean = false): Result<String> = runCatching {
        val preferences = checkNotNull(FrameworkBridge.remotePreferences(HyperSettings.GROUP)) {
            "框架远端设置不可用，未重启桌面"
        }
        // 计划重启本身必须登记，即使安全模式暂时投影为关闭；否则桌面启动会
        // 被误判为异常启动并累计熔断。恢复路径仍由原生状态和恢复令牌单独校验。
        val issued = SystemClock.elapsedRealtime() / 1000L
        val token = if (recover) {
            nextRecoveryToken(System.currentTimeMillis() / 1000L,
                (preferences.all[RECOVERY_SERIAL_KEY] as? Int)?.toLong() ?: 0L)
        } else random.nextInt(PLANNED_RESTART_TOKEN_TAG - 1) + 1
        val request = restartRequest(issued, token, recover)
        val editor = preferences.edit().remove(REQUEST_KEY)
            .remove(PlannedRestarts.key("launcher"))
        if (recover) editor.putInt(RECOVERY_SERIAL_KEY, token)
        // 主开关只用于决定旧端点是否需要打开一次空功能恢复通道。
        // 普通计划重启不得读取或依赖它，否则安全模式投影为关闭时会漏写请求。
        if (recover && preferences.all[LauncherSnapshotWire.MASTER] != true) {
            // 旧端点用主开关决定是否应答：仅打开空功能的恢复通道，不启用任何用户功能。
            editor.putBoolean(LauncherSnapshotWire.MASTER, true)
            for (key in listOf(LauncherSnapshotWire.COUNT, LauncherSnapshotWire.SHARE,
                LauncherSnapshotWire.FREEFORM, LauncherSnapshotWire.TASKS)) editor.putBoolean(key, false)
        }
        check(editor.commit()) { "重启请求写入失败，未重启桌面" }
        val wireToken = if (recover) token else token or PLANNED_RESTART_TOKEN_TAG
        val script = rootRequestScript(Process.myUid() / 100000, issued, wireToken, recover)
        check(RootAccess.exec(script, 10, globalMountNamespace = true)?.trim() == "ready") {
            "root 重启请求未发布，未重启桌面"
        }
        request
    }

    internal fun rootRequestScript(userId: Int, issued: Long, token: Int, recover: Boolean): String {
        require(userId >= 0 && issued in 1..Int.MAX_VALUE.toLong() && token > 0)
        require(recover || token > PLANNED_RESTART_TOKEN_TAG)
        val directory = "/data/user/$userId/com.miui.home/files"
        val target = "$directory/hyperextend_launcher_restart.request"
        val operation = if (recover) REARM_KNOWN else ARM_ABSENT
        val d = '$'
        return "set -e; umask 077; test ! -L '$directory'; test -d '$directory'; " +
            "uid=${d}(stat -c %u '$directory'); " +
            "test \"${d}uid\" -ge 10000; " +
            "tmp=${d}(mktemp '$directory/hyperextend_launcher_restart.XXXXXX'); " +
            "printf '1,%s,$issued,$operation,$token' \"${d}uid\" > \"${d}tmp\"; " +
            "chmod 0444 \"${d}tmp\"; mv -fT \"${d}tmp\" '$target'; echo ready"
    }

    internal fun restartRequest(issued: Long, token: Int, recover: Boolean): String {
        require(issued in 1..Int.MAX_VALUE.toLong() && token > 0 &&
            (recover || token < PLANNED_RESTART_TOKEN_TAG))
        val operation = if (recover) REARM_KNOWN else ARM_ABSENT
        val wireToken = if (recover) token else token or PLANNED_RESTART_TOKEN_TAG
        return "$VERSION|$operation|$issued|$wireToken"
    }

    internal fun nextRecoveryToken(nowSeconds: Long, previous: Long): Int {
        val token = maxOf(nowSeconds, previous + 1)
        require(previous in 0 until Int.MAX_VALUE.toLong() && token in 1..Int.MAX_VALUE.toLong())
        return token.toInt()
    }
    /** 重启命令失败时只撤销自己的请求，不能误删随后创建的新请求。 */
    fun cancelRestart(request: String) {
        if (request.isEmpty()) return
        runCatching {
            val preferences = FrameworkBridge.remotePreferences(HyperSettings.GROUP) ?: return
            if (preferences.all[REQUEST_KEY] == request) {
                preferences.edit().remove(REQUEST_KEY).commit()
            }
            val fields = request.split('|')
            val issued = fields[2].toLong()
            val token = fields[3].toInt()
            val operation = fields[1].toInt()
            require(issued >= 0 && token > 0 && operation in 1..2)
            val directory = "/data/user/${Process.myUid() / 100000}/com.miui.home/files"
            val target = "$directory/hyperextend_launcher_restart.request"
            val d = '$'
            RootAccess.exec("uid=${d}(stat -c %u '$directory'); " +
                "if [ \"${d}(cat '$target')\" = \"1,${d}uid,$issued,$operation,$token\" ]; then " +
                "mv -fT '$target' '$directory/hyperextend_launcher_restart.consumed'; fi",
                10, globalMountNamespace = true)
        }
    }

    /** system_server 端点只接受短时、整形正确的请求；其它值按无请求处理。 */
    internal fun parseRequest(raw: Any?, operation: Int, nowSeconds: Long): Int? {
        if (raw !is String || nowSeconds !in 1..Int.MAX_VALUE.toLong()) return null
        val fields = raw.split('|')
        if (fields.size != 4 || fields[0].toIntOrNull() != VERSION ||
            fields[1].toIntOrNull() != operation || operation !in ARM_ABSENT..REARM_KNOWN) return null
        val issued = fields[2].toLongOrNull() ?: return null
        val token = fields[3].toLongOrNull() ?: return null
        if (issued !in 1..Int.MAX_VALUE.toLong() || token !in 1..Int.MAX_VALUE.toLong() ||
            issued > nowSeconds || nowSeconds - issued > MAX_REQUEST_AGE_SECONDS) return null
        return token.toInt()
    }

    internal fun operationForRequest(raw: Any?): Int? {
        if (raw !is String) return null
        val fields = raw.split('|')
        if (fields.size != 4 || fields[0].toIntOrNull() != VERSION) return null
        val operation = fields.getOrNull(1)?.toIntOrNull() ?: return null
        return operation.takeIf { it in ARM_ABSENT..REARM_KNOWN }
    }

    /** 整机重启会重置开机时钟，先核对跨重启的短时意图，再签发本代际 Binder 租期。 */
    internal fun pending(read: (String) -> Any?, bootSeconds: Long, wallMs: Long): Pending? {
        val direct = read(REQUEST_KEY)
        val operation = operationForRequest(direct)
        if (operation != null && parseRequest(direct, operation, bootSeconds) != null) {
            return Pending(REQUEST_KEY, direct!!, direct as String)
        }
        val key = PlannedRestarts.key("launcher")
        val stored = read(key)
        val request = PlannedRestartRequest.decode(stored) ?: return null
        if (!request.valid(wallMs, 0) || bootSeconds !in 1..Int.MAX_VALUE.toLong()) return null
        val token = (request.token % (PLANNED_RESTART_TOKEN_TAG - 1) + 1).toInt()
        return Pending(key, stored!!, restartRequest(bootSeconds, token, false))
    }

    internal const val REQUEST_KEY_FOR_ENDPOINT = REQUEST_KEY
    internal const val ARM_OPERATION_FOR_ENDPOINT = ARM_ABSENT
    internal const val REARM_OPERATION_FOR_ENDPOINT = REARM_KNOWN
}
