/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import android.content.SharedPreferences
import android.os.Binder
import android.os.Parcel
import android.os.SystemClock
import io.github.YGHFv.HyperExtend.core.LauncherShareBridge
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.Reflect
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime
import java.util.concurrent.atomic.AtomicInteger

/** 桌面配置通道的 system_server 应答端点。作用域：system_server。 */
internal object LauncherSnapshotEndpoint {

    /** 重复应答的日志上限：桌面弹菜单时会高频调用，不能每帧写日志。 */
    private const val REPORT_LIMIT = 8

    /**
     * 分享日志使用独立额度。
     *
     * 普通快照会在弹菜单时高频到达并先耗尽 [REPORT_LIMIT]；若分享排队继续共用
     * 该计数，真正点击分享时最关键的“是否进入 system_server”证据会被静默丢弃。
     * 分享最多只有主、次两个调用点，单独限次仍能防止重复点击刷屏。
     */
    private const val SHARE_REPORT_LIMIT = 8

    private val reported = AtomicInteger(0)
    private val shareReported = AtomicInteger(0)
    private val revisions = AtomicInteger(0)

    private val maintenanceLock = Any()

    @Volatile
    private var installed = false

    /** 框架托管的偏好句柄。安装时绑定一次，事务路径只读。 */
    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 装到 `IWindowManager$Stub#onTransact` 上。只能在系统服务就绪之后调用
     * （见 RotationLockGuard 文件头的「铁律一」），本类的调用点与它保持一致。
     */
    fun install(loader: ClassLoader, settings: HookSettings): Int {
        if (installed) return 0
        if (SafeModeRuntime.blocked) {
            ModuleLog.warn("launcher endpoint: safe mode engaged — not installing")
            return 0
        }
        val stub = Reflect.loadClass(loader, "android.view.IWindowManager\$Stub")
        if (stub == null) {
            ModuleLog.warn("launcher endpoint: android.view.IWindowManager\$Stub not found")
            return 0
        }
        val method = Reflect.findMethod(
            stub, "onTransact", Int::class.javaPrimitiveType!!, Parcel::class.java,
            Parcel::class.java, Int::class.javaPrimitiveType!!,
        )
        if (method == null) {
            ModuleLog.warn("launcher endpoint: IWindowManager\$Stub#onTransact not found")
            return 0
        }
        prefs = settings.prefs
        val ok = HookRuntime.hook(method, "launcher_snapshot/IWindowManager#onTransact") { chain ->
            // 先让系统按自己的规则处理。我们的自定义码在标准分发里走 default 分支
            // （返回 false），所以**必须在 proceed 之后**再写回复体 —— 写早了会被
            // 「事务失败」的清理路径连同清掉，最终回复是一段垃圾。
            val original = chain.proceed()
            val transaction = chain.args[0] as? Int ?: return@hook original
            if (transaction != LauncherSnapshotWire.TRANSACTION &&
                transaction != LauncherSnapshotWire.MAINTENANCE_TRANSACTION &&
                transaction != LauncherSnapshotWire.SHARE_TRANSACTION) return@hook original
            runCatching {
                if (transaction == LauncherSnapshotWire.TRANSACTION) {
                    answerSnapshot(chain.args[1] as? Parcel, chain.args[2] as? Parcel)
                } else if (transaction == LauncherSnapshotWire.MAINTENANCE_TRANSACTION) {
                    answerMaintenance(chain.args[2] as? Parcel, transaction)
                } else {
                    // 分享转发含 PackageManager 与文件打开，不能拖住 window 事务线程；
                    // 立即写入“已排队”回复，后台完成后再决定是否启动 chooser。
                    answerShare(chain.args[1] as? Parcel, chain.args[2] as? Parcel)
                }
            }
                .onFailure { ModuleLog.error("launcher endpoint: reply failed", it) }
            // 强制 onTransact 返回 true：这是「按自定义码应答」的协议要求，
            // 返回 false 会被 binder 当成事务失败，我们刚写好的回复体就送不出去。
            // 只对**我们自己的码**这样做，其它码一律透传原结果。
            true
        }
        if (ok) installed = true
        ModuleLog.info("launcher endpoint: installed=$ok (transaction=${LauncherSnapshotWire.TRANSACTION})")
        return if (ok) 1 else 0
    }

    private fun answerShare(data: Parcel?, reply: Parcel?) {
        if (data == null || reply == null) return
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val cmdline = readCmdline(pid)
        val accepted = if (isLauncherCaller(uid, pid, cmdline)) {
            runCatching {
                data.setDataPosition(0)
                require(data.readInt() == LauncherSnapshotWire.SHARE_PROTOCOL) { "share protocol" }
                val point = data.readInt()
                require(point == LauncherSnapshotWire.SHARE_POINT_PRIMARY ||
                    point == LauncherSnapshotWire.SHARE_POINT_SECONDARY) { "share point" }
                val packageName = data.readString()
                require(LauncherShareBridge.packagePolicy(packageName) == packageName) {
                    "share package"
                }
                LauncherShareEndpoint.enqueue(packageName!!).also { queued ->
                    if (queued) {
                        reportShare("share queued point=$point")
                    }
                }
            }.getOrElse {
                reportShare(
                    "share rejected uid=$uid pid=$pid reason=${it.javaClass.simpleName}",
                )
                false
            }
        } else {
            reportShare("share rejected caller uid=$uid pid=$pid")
            false
        }
        reply.setDataPosition(0)
        reply.writeInt(if (accepted) LauncherSnapshotWire.SHARE_QUEUED_ACK else
            LauncherSnapshotWire.SHARE_REJECTED_ACK)
        reply.writeInt(LauncherSnapshotWire.SHARE_PROTOCOL)
        reply.setDataPosition(0)
    }

    /**
     * 这一次事务要不要应答、应答什么。
     *
     * 由拦截体在 `chain.proceed()` **之后**调用：标准分发对自定义码返回 false 并会
     * 清理回复体，所以必须等它跑完再写我们的内容，并把读写游标归零。
     */
    private fun answerSnapshot(data: Parcel?, reply: Parcel?) {
        if (data == null || reply == null) return
        val uid = Binder.getCallingUid()
        val pid = Binder.getCallingPid()
        val cmdline = readCmdline(pid)
        val snapshot = if (isLauncherCaller(uid, pid, cmdline)) {
            val seconds = SystemClock.elapsedRealtime() / 1000L
            val pending = LauncherSafetyMaintenance.pending(::readSetting, seconds, System.currentTimeMillis())
            val operation = LauncherSafetyMaintenance.operationForRequest(pending?.request)
            val maintenanceToken = operation?.let {
                LauncherSafetyMaintenance.parseRequest(pending?.request, it, seconds)
            } ?: 0
            buildLauncherSnapshot(
                revision = nextRevision(),
                issuedSeconds = seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                read = ::readSetting,
                resetToken = maintenanceToken,
            )
        } else {
            reportOnce("rejected caller uid=$uid pid=$pid")
            null
        }
        val words = snapshot?.toWire() ?: LauncherSnapshotWire.denied()
        reply.setDataPosition(0)
        for (word in words) reply.writeInt(word)
        // 写完必须把游标放回开头，宿主随后从 0 开始读时才是完整内容。
        reply.setDataPosition(0)
        if (snapshot != null) {
            reportOnce("served revision=${snapshot.revision} features=${snapshot.features} limit=${snapshot.countLimit}")
        }
    }

    /** 只消费由设置页明确发起的一次维护请求，不把它混进普通配置快照。 */
    private fun answerMaintenance(reply: Parcel?, transaction: Int) {
        if (reply == null) return
        synchronized(maintenanceLock) {
            val now = (SystemClock.elapsedRealtime() / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong())
            val pending = LauncherSafetyMaintenance.pending(::readSetting, now, System.currentTimeMillis())
            val operation = LauncherSafetyMaintenance.operationForRequest(
                pending?.request,
            ) ?: run {
                reply.setDataPosition(0)
                LauncherSnapshotWire.deniedMaintenance().forEach(reply::writeInt)
                reply.setDataPosition(0)
                return
            }
            val callerPid = Binder.getCallingPid()
            val callerUid = Binder.getCallingUid()
            val token = if (isLauncherCaller(callerUid, callerPid, readCmdline(callerPid))) {
                LauncherSafetyMaintenance.parseRequest(
                    pending?.request, operation, now,
                )
            } else null
            if (token == null || now <= 0L) {
                reportOnce("maintenance request rejected")
                reply.setDataPosition(0)
                LauncherSnapshotWire.deniedMaintenance().forEach(reply::writeInt)
                reply.setDataPosition(0)
                return
            }
            // 一次性消费：事务成功写出授权材料后，旧请求不能再次被另一代桌面使用。
            val consumed = pending != null && readSetting(pending.key) == pending.stored &&
                prefs?.edit()?.remove(pending.key)?.commit() == true
            if (!consumed) {
                reportOnce("maintenance request could not be consumed")
                reply.setDataPosition(0)
                LauncherSnapshotWire.deniedMaintenance().forEach(reply::writeInt)
                reply.setDataPosition(0)
                return
            }
            val issued = now.toInt()
            val expires = (now + LauncherSnapshotWire.LEASE_SECONDS).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            reply.setDataPosition(0)
            intArrayOf(
                LauncherSnapshotWire.MAINTENANCE_ACK,
                LauncherSnapshotWire.MAINTENANCE_PROTOCOL,
                operation,
                callerPid,
                callerUid,
                issued,
                expires,
                token,
            ).forEach(reply::writeInt)
            reply.setDataPosition(0)
            reportOnce("maintenance grant issued operation=$operation")
        }
    }

    /**
     * 读取一个设置键的**原始值**。
     *
     * 刻意不用 `HookSettings.isOn`：那个函数还会做主开关联查与「目录里没有就为 false」
     * 的判定，而这里需要的是「这个键上用户到底写了什么」，再由纯函数统一收敛。
     * 读的是框架托管的 RemotePreferences，与界面是同一份数据。
     */
    private fun readSetting(key: String): Any? = Reflect.attempt { prefs?.all?.get(key) }

    /** 严格递增的发送序号；溢出时回绕到 1（原生侧只要求大于上一次）。 */
    private fun nextRevision(): Int = revisions.updateAndGet { current ->
        if (current >= Int.MAX_VALUE) 1 else current + 1
    }

    private fun reportOnce(message: String) {
        if (reported.getAndIncrement() < REPORT_LIMIT) {
            ModuleLog.info("launcher endpoint: $message")
        }
    }

    private fun reportShare(message: String) {
        if (shareReported.getAndIncrement() < SHARE_REPORT_LIMIT) {
            ModuleLog.info("launcher endpoint: $message")
        }
    }
}
