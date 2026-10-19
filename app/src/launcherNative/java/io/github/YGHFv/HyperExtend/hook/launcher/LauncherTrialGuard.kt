/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.launcher

import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class LauncherTrialRecord(val resetToken: Long, val attempt: String, val pending: Boolean) {
    fun encode(): String = "1|$resetToken|${if (pending) "P" else "H"}|$attempt\n"

    companion object {
        private val attemptPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun decode(text: String): LauncherTrialRecord {
            val fields = text.removeSuffix("\n").split('|')
            require(fields.size == 4 && fields[0] == "1") { "试验记录版本或字段无效" }
            val reset = fields[1].toLongOrNull()
            require(reset != null && reset >= 0 && attemptPattern.matches(fields[3])) { "试验记录令牌无效" }
            val pending = when (fields[2]) { "P" -> true; "H" -> false; else -> error("试验记录状态无效") }
            return LauncherTrialRecord(reset, fields[3], pending)
        }
    }
}

internal fun interface LauncherTrialJournal {
    /** 读取、决策、持久化必须在同一个跨进程互斥区间；只有确实不存在时才能传入 null。 */
    fun transaction(update: (LauncherTrialRecord?) -> LauncherTrialRecord): Result<LauncherTrialRecord>
}

/** 独立的安装试验保护，不修改系统属性、不启动 Activity，也不自动连接到原型预检。 */
@Keep
internal class LauncherTrialGuard(private val journal: LauncherTrialJournal) {
    fun arm(resetToken: Long): Result<LauncherTrialRecord> = journal.transaction { previous ->
        require(resetToken >= 0 && (previous == null || resetToken >= previous.resetToken)) { "恢复令牌过期" }
        check(previous == null || !previous.pending || resetToken > previous.resetToken) {
            "上次原生试验没有完成健康确认，保持停用"
        }
        LauncherTrialRecord(resetToken, UUID.randomUUID().toString(), pending = true)
    }

    /** 只能在未来独立的运行期健康验收后调用；加载成功或配置预检通过都不算健康。 */
    fun acknowledgeHealthy(ticket: LauncherTrialRecord): Result<LauncherTrialRecord> = journal.transaction { previous ->
        check(ticket.pending && previous == ticket) { "试验票据过期或不属于当前试验" }
        ticket.copy(pending = false)
    }
}

/** 目录必须来自已核验宿主的私有 Context，不得由偏好值或进程名拼接。 */
@Keep
internal class LauncherTrialFileJournal(
    private val directory: Path,
    private val syncDirectory: (Path) -> Unit = ::syncAndroidDirectory,
) : LauncherTrialJournal {
    private val recordPath = directory.resolve("hyperextend_launcher_trial")
    private val lockPath = directory.resolve("hyperextend_launcher_trial.lock")

    override fun transaction(update: (LauncherTrialRecord?) -> LauncherTrialRecord): Result<LauncherTrialRecord> = runCatching {
        require(directory.isAbsolute && Files.isDirectory(directory, NOFOLLOW_LINKS)) { "私有目录不可用" }
        FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = checkNotNull(channel.tryLock()) { "另一个进程正在修改试验记录" }
            lock.use {
                val next = update(readRecord())
                check(LauncherTrialRecord.decode(next.encode()) == next) { "待写入试验记录无效" }
                persist(next)
                next
            }
        }
    }

    private fun readRecord(): LauncherTrialRecord? {
        val attributes = try {
            Files.readAttributes(recordPath, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        require(attributes.isRegularFile && attributes.size() in 1..512) { "试验记录不是有效普通文件" }
        val bytes = ByteArray(513)
        val count = Files.newInputStream(recordPath, READ, NOFOLLOW_LINKS).use { input ->
            var size = 0
            while (size < bytes.size) {
                val read = input.read(bytes, size, bytes.size - size)
                if (read < 0) break
                check(read > 0) { "试验记录读取没有进展" }
                size += read
            }
            size
        }
        require(count in 1..512) { "试验记录被截断或过大" }
        return LauncherTrialRecord.decode(String(bytes, 0, count, Charsets.UTF_8))
    }

    private fun persist(record: LauncherTrialRecord) {
        val temporary = Files.createTempFile(directory, "launcher-trial-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { stream ->
                stream.write(record.encode().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // 不支持原子替换就拒绝，不能退回复制/删后重建，留下无保护窗口。
            Files.move(temporary, recordPath, ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(directory)
            check(readRecord() == record) { "试验记录写后验证失败" }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}

private fun syncAndroidDirectory(directory: Path) {
    val fd = Os.open(directory.toString(), OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
        OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
    try {
        check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "同步目标已不是目录" }
        Os.fsync(fd)
    } finally { Os.close(fd) }
}
