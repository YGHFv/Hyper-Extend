package io.github.YGHFv.HyperExtend.hook.launcher

import java.io.InputStream
import java.util.zip.ZipFile

internal object LauncherHostContract {
    const val PACKAGE = "com.miui.home"
    const val RUNTIME_LIBRARY = "hyperos.rustruntime.v5"
    const val ENTRY_LIBRARY = "libapp_launcher.so"
    const val IMAGE_ENTRY = "lib/arm64-v8a/libapp.so"
}

internal data class LauncherProcessSnapshot(
    val pid: Int,
    val uid: Int,
    val startTicks: Long,
    val command: String,
    val executable: String,
    val is64Bit: Boolean,
) {
    fun isDirectJavaCandidate(): Boolean = pid > 0 && uid >= 10000 && startTicks > 0 && is64Bit &&
        command == LauncherHostContract.PACKAGE && executable in setOf(
            "/system/bin/app_process64", "/apex/com.android.art/bin/app_process64")
}

internal data class LauncherApplicationSnapshot(
    val packageName: String,
    val processName: String,
    val uid: Int,
    val version: Long,
    val hasCode: Boolean,
    val hyperOsPackage: Boolean,
    val entryLibrary: String,
    val sourceApk: String,
    val sourceStamp: String,
    val lastUpdateTime: Long,
    val hasSplits: Boolean,
    val sharedUserId: String?,
) {
    fun matches(process: LauncherProcessSnapshot): Boolean =
        packageName == LauncherHostContract.PACKAGE && processName == packageName && uid == process.uid &&
            !hasCode && hyperOsPackage &&
            entryLibrary == LauncherHostContract.ENTRY_LIBRARY && sourceApk.startsWith('/') &&
            sourceStamp.isNotBlank() && lastUpdateTime >= 0 && !hasSplits && sharedUserId == null
}

internal interface LauncherIdentitySource {
    fun process(): LauncherProcessSnapshot
    fun application(): LauncherApplicationSnapshot
    fun imageCompatible(sourceApk: String): Boolean
}

internal data class LauncherCapturedIdentity(
    val process: LauncherProcessSnapshot,
    val application: LauncherApplicationSnapshot,
) {
    fun configurationEvidence(): LauncherProcessEvidence = LauncherProcessEvidence(
        pid = process.pid, uid = process.uid, packageUid = application.uid,
        packageName = application.packageName, processName = process.command,
        versionCode = application.version, postSpecializationConfirmed = true, spawner = false,
    )
}

internal object LauncherIdentity {
    fun capture(source: LauncherIdentitySource): Result<LauncherCapturedIdentity> = runCatching {
        val process = source.process()
        check(process.isDirectJavaCandidate()) { "不是已支持的直接 Java 桌面进程" }
        val application = source.application()
        check(application.matches(process)) { "当前应用与桌面宿主合同不符" }
        check(source.imageCompatible(application.sourceApk)) { "桌面目标库格式不符" }
        check(source.application() == application && source.process() == process) { "身份核验期间宿主或进程代际发生变化" }
        LauncherCapturedIdentity(process, application)
    }

    fun commandName(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        require(end > 0 && bytes.drop(end).all { it == 0.toByte() }) { "进程命令行格式不完整" }
        require(bytes.take(end).all { it in 33..126 }) { "进程命令行编码不符" }
        return String(bytes, 0, end, Charsets.US_ASCII)
    }

    fun startTicks(stat: String, expectedPid: Int): Long {
        require(expectedPid > 0 && stat.startsWith("$expectedPid (")) { "进程统计的 PID 不符" }
        val end = stat.lastIndexOf(')')
        require(end >= 0 && stat.getOrNull(end + 1) == ' ') { "进程统计格式不完整" }
        val fields = stat.substring(end + 2).trim().split(Regex("\\s+"))
        require(fields.size >= 50 && fields[0].length == 1) { "进程统计字段缺失" }
        return fields[19].toLongOrNull()?.takeIf { it > 0 } ?: error("进程启动代际无效")
    }

    fun boundedBytes(input: InputStream, limit: Int): ByteArray {
        require(limit in 1..65536)
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            check(count > 0) { "身份材料读取没有进展" }
            size += count
        }
        require(size <= limit) { "身份材料超过读取上限" }
        return buffer.copyOf(size)
    }

    // 这里只验证容器和架构；最终许可必须来自原生端对 Hook 点参数与返回 ABI 的检查。
    fun apkImageCompatible(sourceApk: String): Boolean = ZipFile(sourceApk).use { archive ->
        val entries = archive.entries()
        var image: java.util.zip.ZipEntry? = null
        var manifest: java.util.zip.ZipEntry? = null
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            check(!Regex("classes(?:[0-9]+)?\\.dex").matches(entry.name)) { "宿主出现未审核的 DEX" }
            if (entry.name == LauncherHostContract.IMAGE_ENTRY) {
                check(image == null && !entry.isDirectory && entry.size >= 64) { "目标库不唯一或已截断" }
                image = entry
            }
            if (entry.name == "AndroidManifest.xml") {
                check(manifest == null && !entry.isDirectory && entry.size > 0) { "宿主清单不唯一或为空" }
                manifest = entry
            }
        }
        checkNotNull(manifest) { "宿主清单缺失" }
        archive.getInputStream(checkNotNull(image) { "宿主目标库缺失" }).use {
            val header = ByteArray(64)
            var size = 0
            while (size < header.size) {
                val count = it.read(header, size, header.size - size)
                check(count > 0) { "目标 ELF 头读取不完整" }
                size += count
            }
            header.take(7) == listOf<Byte>(0x7f, 0x45, 0x4c, 0x46, 2, 1, 1) &&
                header[16] == 3.toByte() && header[17] == 0.toByte() &&
                header[18] == 0xb7.toByte() && header[19] == 0.toByte()
        }
    }
}
