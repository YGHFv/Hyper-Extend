/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.milink

/**
 * 乐播 SDK 共享存储目录的归属判断。
 *
 * 当前缓存的 MIUI 通道在 `Session#getSDPackageDir` 返回
 * `Xiaomi/com.milink.service`，随后拼成
 * `共享存储/Xiaomi/com.milink.service/sink/sdk` 并交给 `ContextPath#makeDir`。
 * 旧版可能直接使用包名目录，所以两者都识别；只认共享存储根目录下的完整目录段，
 * 不碰应用私有数据目录，也不匹配仅以包名开头的相似目录。
 */
internal object MilinkHpplayPolicy {
    private val ownedRelativeDirectories = listOf(
        "Xiaomi/com.milink.service",
        "com.milink.service",
    )

    fun shouldBlock(externalRoot: String, directory: String?): Boolean {
        if (directory.isNullOrBlank()) return false
        val root = normalize(externalRoot)
        val candidate = normalize(directory)
        if (root.isEmpty() || !candidate.startsWith("$root/")) return false
        val relative = candidate.removePrefix("$root/")
        return ownedRelativeDirectories.any { owned ->
            relative == owned || relative.startsWith("$owned/")
        }
    }

    private fun normalize(path: String): String = path.replace('\\', '/').trimEnd('/')
}
