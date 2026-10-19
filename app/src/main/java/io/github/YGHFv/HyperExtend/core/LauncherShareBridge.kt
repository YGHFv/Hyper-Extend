/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 桌面原生分享桥的模块侧实现。
 *
 * system_server 只能把已打开的 APK / split 文件描述符递过来；本进程负责把它们
 * 落到模块私有目录，再构造与安全中心“分享”相同的 ACTION_SEND + chooser。
 * 控制 Provider 只接受 system_server 或本模块 UID，文件 Provider 只读固定目录。
 */
internal object LauncherShareBridge {
    const val CONTROL_AUTHORITY = "io.github.YGHFv.HyperExtend.launcher.share"
    const val FILE_AUTHORITY = "io.github.YGHFv.HyperExtend.launcher.files"
    const val MODULE_PACKAGE = "io.github.YGHFv.HyperExtend"
    const val LAUNCH_ACTIVITY = "io.github.YGHFv.HyperExtend.ui.SettingsActivity"
    const val LAUNCH_ACTION = "io.github.YGHFv.HyperExtend.action.LAUNCH_SHARE"
    const val EXTRA_SHARE_URI = "io.github.YGHFv.HyperExtend.extra.SHARE_URI"
    const val METHOD_SHARE = "share_apk"
    const val PROTOCOL = 1
    const val APK_MIME = "application/vnd.android.package-archive"
    const val APKS_MIME = "application/zip"
    const val MAX_PACKAGE_LENGTH = 255
    const val MAX_SPLITS = 64
    const val MAX_FILE_BYTES = 8L * 1024L * 1024L * 1024L
    /** 一次请求的主 APK 与所有 split 合计上限，避免 64 个分包各占 8 GiB。 */
    const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L

    private val outputStamp = AtomicLong()

    fun packagePolicy(packageName: String?): String? {
        if (packageName == null || packageName.isEmpty() || packageName.length > MAX_PACKAGE_LENGTH) {
            return null
        }
        // 包名不允许空段；Android 的 PackageManager 也不会接受开头、连续或结尾的点。
        if (packageName.startsWith('.') || packageName.endsWith('.') ||
            packageName.contains("..")) {
            return null
        }
        for (index in packageName.indices) {
            val value = packageName[index]
            val first = index == 0
            val allowed = value in 'a'..'z' ||
                (!first && value in '0'..'9') ||
                (!first && (value == '.' || value == '_' || value == '-'))
            if (!allowed) return null
        }
        return packageName
    }

    fun splitLabels(names: Array<String?>?): List<String> {
        if (names == null || names.size > MAX_SPLITS) return emptyList()
        return names.mapIndexedNotNull { index, raw ->
            val value = raw ?: return@mapIndexedNotNull "split_$index"
            val safe = value.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64)
            if (safe.isEmpty()) "split_$index" else safe
        }
    }

    /**
     * 把主 APK 与 splits 复制为可分享文件。单个 APK 直接输出 .apk；分包输出
     * STORED `.apks`，避免重新压缩已经压缩过的 APK 内容。
     */
    fun materialize(
        context: Context,
        packageName: String,
        main: ParcelFileDescriptor,
        splits: List<ParcelFileDescriptor>,
        labels: List<String>,
        outputName: String = nextOutputName(packageName, splits.isEmpty()),
    ): File {
        check(packagePolicy(packageName) != null) { "invalid share package" }
        check(splits.size <= MAX_SPLITS) { "too many splits" }
        check(outputName.matches(OUTPUT_NAME)) { "invalid share output name" }
        val directory = File(context.filesDir, "launcher_share").apply {
            check(isDirectory || mkdirs()) { "cannot create share directory" }
        }
        val tempDirectory = File(context.cacheDir, "launcher_share_tmp").apply {
            check(isDirectory || mkdirs()) { "cannot create share temp directory" }
        }
        val output = File(directory, outputName)
        check(output.createNewFile()) { "share output already exists" }
        val budget = ByteBudget(MAX_TOTAL_BYTES)
        try {
            if (splits.isEmpty()) {
                FileInputStream(main.fileDescriptor).use { input ->
                    FileOutputStream(output).use { target ->
                        copyLimited(input, target, budget)
                    }
                }
            } else {
                ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
                    zip.setMethod(ZipOutputStream.STORED)
                    addStored(zip, "base.apk", main, budget, tempDirectory)
                    splits.forEachIndexed { index, fd ->
                        val label = labels.getOrNull(index) ?: "split_$index"
                        addStored(zip, "$label.apk", fd, budget, tempDirectory)
                    }
                }
            }
            return output
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
    }

    fun outputName(packageName: String, stamp: Long, singleApk: Boolean): String {
        // 文件名上限比包名短，过长的包名用稳定摘要收尾，既保留可读前缀
        // 又不会因超过文件系统 255 字节限制而让分享直接失败。
        val stem = if (packageName.length <= MAX_STEM_LENGTH) {
            packageName
        } else {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(packageName.toByteArray(Charsets.UTF_8))
                .take(8)
                .joinToString("") { "%02x".format(it) }
            "${packageName.take(MAX_STEM_PREFIX).trimEnd('.', '-', '_')}-$digest"
        }
        return "$stem-$stamp.${if (singleApk) "apk" else "apks"}"
    }

    /**
     * 用单调递增的毫秒序号生成默认文件名。仅用毫秒时间戳时，同一毫秒内的
     * 两个点击会覆盖同一个输出文件；这里把冲突窗口推迟到进程内的下一次调用。
     */
    private fun nextOutputName(packageName: String, singleApk: Boolean): String {
        val stamp = outputStamp.updateAndGet { previous ->
            maxOf(System.currentTimeMillis(), previous + 1L)
        }
        return outputName(packageName, stamp, singleApk)
    }

    fun shareIntent(file: File): Intent {
        val authority = FILE_AUTHORITY
        val uri = Uri.parse("content://$authority/${Uri.encode(file.name)}")
        val mime = if (file.name.endsWith(".apks")) APKS_MIME else APK_MIME
        return Intent(Intent.ACTION_SEND).apply {
            setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setType(mime)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
        }
    }

    fun launch(context: Context, file: File): Boolean {
        val chooser = Intent.createChooser(shareIntent(file), null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        return true
    }

    fun launchFromPending(context: Context, uri: Uri): Boolean {
        val file = fileFor(context, uri.lastPathSegment ?: return false) ?: return false
        val chooser = Intent.createChooser(shareIntent(file), null)
        context.startActivity(chooser)
        cleanup(context)
        return true
    }

    fun fileFor(context: Context, name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) {
            return null
        }
        val directory = File(context.filesDir, "launcher_share")
        val file = File(directory, name)
        return if (file.isFile && file.parentFile?.canonicalFile == directory.canonicalFile) file else null
    }

    fun mimeOf(file: File): String = if (file.name.endsWith(".apks")) APKS_MIME else APK_MIME

    fun cleanup(context: Context) {
        val directory = File(context.filesDir, "launcher_share")
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun copyLimited(
        input: FileInputStream,
        output: FileOutputStream,
        budget: ByteBudget,
    ) {
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            budget.consume(count.toLong())
            output.write(buffer, 0, count)
        }
    }

    private fun addStored(
        zip: ZipOutputStream,
        name: String,
        fd: ParcelFileDescriptor,
        budget: ByteBudget,
        tempDirectory: File,
    ) {
        FileInputStream(fd.fileDescriptor).use { input ->
            // 不依赖系统默认临时目录：模块的 cacheDir 在受限应用环境中更可靠。
            val temporary = File.createTempFile("launcher-share-size-", ".tmp", tempDirectory)
            try {
                var size = 0L
                val buffer = ByteArray(256 * 1024)
                FileOutputStream(temporary).use { target ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        budget.consume(count.toLong())
                        size += count
                        target.write(buffer, 0, count)
                    }
                }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    this.size = size
                    compressedSize = size
                    crc = crc32(temporary)
                }
                zip.putNextEntry(entry)
                FileInputStream(temporary).use { it.copyTo(zip) }
                zip.closeEntry()
            } finally {
                temporary.delete()
            }
        }
    }

    private fun crc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        FileInputStream(file).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value
    }

    private val OUTPUT_NAME =
        Regex("^[a-z][a-z0-9._-]{0,215}-[0-9]{1,19}\\.(?:apk|apks)$")
    private const val MAX_STEM_LENGTH = 216
    private const val MAX_STEM_PREFIX = 199

    private class ByteBudget(private var remaining: Long) {
        fun consume(amount: Long) {
            check(amount >= 0L && amount <= remaining) { "share payload too large" }
            remaining -= amount
        }
    }
}
