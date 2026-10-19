package io.github.YGHFv.HyperExtend.hook.launcher

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File

@Keep
internal object LauncherAndroidIdentity : LauncherIdentitySource {
    fun capture(): Result<LauncherCapturedIdentity> = LauncherIdentity.capture(this)

    override fun process(): LauncherProcessSnapshot {
        check(Build.VERSION.SDK_INT >= 28 && Process.is64Bit()) { "不支持的 Java 进程架构" }
        val pid = Process.myPid()
        val uid = Process.myUid()
        val firstTicks = readStartTicks(pid)
        val command = File("/proc/self/cmdline").inputStream().use {
            LauncherIdentity.commandName(LauncherIdentity.boundedBytes(it, 512))
        }
        val executable = Os.readlink("/proc/self/exe")
        check(firstTicks == readStartTicks(pid) && pid == Process.myPid() && uid == Process.myUid()) { "进程采样不稳定" }
        return LauncherProcessSnapshot(pid, uid, firstTicks, command, executable, true)
    }

    override fun application(): LauncherApplicationSnapshot {
        check(Build.VERSION.SDK_INT >= 28) { "不支持的 Java 应用身份接口" }
        val application = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication")
            .invoke(null) as? Application ?: error("当前进程没有完成 Java 应用绑定")
        check(application.packageName == LauncherHostContract.PACKAGE &&
            Application.getProcessName() == LauncherHostContract.PACKAGE) { "当前绑定应用不是桌面主进程" }
        val info = application.packageManager.getPackageInfo(LauncherHostContract.PACKAGE,
            PackageManager.GET_META_DATA)
        val installed = checkNotNull(info.applicationInfo) { "包管理器应用信息缺失" }
        val bound = application.applicationInfo
        check(bound.packageName == info.packageName && bound.uid == installed.uid &&
            bound.uid == Process.myUid() && bound.processName == installed.processName &&
            bound.sourceDir == installed.sourceDir && bound.splitSourceDirs.contentEquals(installed.splitSourceDirs) &&
            (bound.flags and ApplicationInfo.FLAG_HAS_CODE) == (installed.flags and ApplicationInfo.FLAG_HAS_CODE)) {
            "绑定应用与包管理器记录不一致"
        }
        val path = checkNotNull(installed.sourceDir) { "宿主 APK 路径缺失" }
        check(File(path).isAbsolute && File(path).canonicalPath == path) { "宿主 APK 路径不规范" }
        val stat = Os.lstat(path)
        check(OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0) { "宿主 APK 不是普通文件" }
        val metadata = installed.metaData
        return LauncherApplicationSnapshot(
            packageName = info.packageName, processName = installed.processName.orEmpty(), uid = installed.uid,
            version = info.longVersionCode, hasCode = installed.flags and ApplicationInfo.FLAG_HAS_CODE != 0,
            hyperOsPackage = metadata?.get("hyperos_package") == true,
            entryLibrary = metadata?.get("hyperos_app_lib_name") as? String ?: "",
            sourceApk = path,
            sourceStamp = "${stat.st_dev}:${stat.st_ino}:${stat.st_size}:${stat.st_mtim.tv_sec}:${stat.st_mtim.tv_nsec}",
            lastUpdateTime = info.lastUpdateTime, hasSplits = !installed.splitSourceDirs.isNullOrEmpty(),
            sharedUserId = info.sharedUserId,
        )
    }

    override fun imageCompatible(sourceApk: String): Boolean = LauncherIdentity.apkImageCompatible(sourceApk)

    private fun readStartTicks(pid: Int): Long = File("/proc/self/stat").inputStream().use {
        LauncherIdentity.startTicks(String(LauncherIdentity.boundedBytes(it, 8192), Charsets.US_ASCII), pid)
    }
}
