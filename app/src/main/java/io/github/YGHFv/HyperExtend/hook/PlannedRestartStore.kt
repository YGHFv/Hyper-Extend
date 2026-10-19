/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.core.PlannedRestartRequest
import java.io.File
import java.io.FileOutputStream
import android.system.Os
import android.system.OsConstants

/** 先持久化消费记录再免计数；同一请求不能豁免随后真实的连续异常退出。 */
internal class PlannedRestartStore(private val file: File,
    private val syncDirectory: (File) -> Unit = { directory ->
        val fd = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) { "主动重启记录父路径不是目录" }
            Os.fsync(fd)
        } finally { Os.close(fd) }
    },
) {
    fun consume(raw: Any?, now: Long): Boolean {
        val request = PlannedRestartRequest.decode(raw) ?: return false
        val consumed = if (!file.exists()) 0L else {
            require(file.isFile && file.length() in 1..32) { "主动重启消费记录损坏" }
            file.readText().toLongOrNull()?.takeIf { it >= 0 } ?: error("主动重启消费记录无效")
        }
        if (!request.valid(now, consumed)) return false
        val parent = checkNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "主动重启记录目录不可写" }
        val temporary = File(parent, file.name + ".tmp")
        check(!temporary.exists()) { "主动重启消费事务有异常残留" }
        FileOutputStream(temporary).use { stream ->
            stream.write(request.token.toString().toByteArray(Charsets.US_ASCII))
            stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "主动重启消费记录发布失败" }
        syncDirectory(parent)
        check(file.readText() == request.token.toString()) { "主动重启消费记录读回失败" }
        return true
    }
}
