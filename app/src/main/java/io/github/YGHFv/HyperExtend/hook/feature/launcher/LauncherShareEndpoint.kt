/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.YGHFv.HyperExtend.core.LauncherShareBridge
import io.github.YGHFv.HyperExtend.core.ModuleLog
import io.github.YGHFv.HyperExtend.hook.Reflect
import java.util.concurrent.atomic.AtomicInteger

/**
 * system_server 到模块 Provider 的异步转发。
 *
 * 事务线程只解析、校验并回复“已排队”；APK 打开、Provider 文件物化在后台
 * 线程完成，最终由模块 Activity 在主线程创建 chooser。
 * 文件描述符必须在 system_server 打开，因为模块进程未必能读取 `/data/app`
 * 下的目标包。
 */
internal object LauncherShareEndpoint {
    private const val REPORT_LIMIT = 8
    /**
     * 每次异步转发单独计数。
     *
     * 一条完整链路至少会记录 APK 解析、Provider 接受、Activity 拉起三个阶段，
     * 若所有请求共用一个进程级额度，成功尝试几次后就会静默丢失后续证据。
     * 单次请求上限仍可阻止异常循环刷屏，同时让每次点击都有可核对的阶段。
     */
    private class Reporter {
        private val reported = AtomicInteger(0)

        fun report(message: () -> String) {
            if (reported.getAndIncrement() < REPORT_LIMIT) {
                ModuleLog.info("launcher share: ${message()}")
            }
        }
    }

    /** 返回 true 只表示请求已排队，不代表 chooser 已启动。 */
    fun enqueue(packageName: String): Boolean = runCatching {
        val reporter = Reporter()
        Thread({ forward(packageName, reporter) }, "launcher-share").start()
        true
    }.getOrElse {
        Reporter().report { "queue failed package=$packageName reason=${it.javaClass.simpleName}" }
        false
    }

    /**
     * 构造拉起模块 Activity 的一次性 Intent。
     *
     * system_server 只负责把已物化的文件名交给模块进程；URI 只允许固定 Provider
     * Authority 加单个编码后的文件名。这里不调用 `grantUriPermission`：system_server
     * 不是模块 Provider 的 URI 所有者，先授予再启动会在部分系统版本上直接抛错。
     */
    internal fun launchIntent(fileName: String): Intent? {
        val safeName = safeShareFileName(fileName) ?: return null
        return Intent(LauncherShareBridge.LAUNCH_ACTION)
            .setClassName(
                LauncherShareBridge.MODULE_PACKAGE,
                LauncherShareBridge.LAUNCH_ACTIVITY,
            )
            .putExtra(LauncherShareBridge.EXTRA_SHARE_URI, shareFileUri(safeName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 文件出口只接受一个文件名，不接受路径、查询串或额外 Authority。 */
    internal fun shareFileUri(fileName: String): Uri =
        Uri.parse("content://${LauncherShareBridge.FILE_AUTHORITY}/${Uri.encode(fileName)}")

    /**
     * 模块返回的只是文件名；system_server 不信任其中的任何路径语义。
     * 这里与 `LauncherShareBridge.fileFor` 保持同一拒绝边界，避免生产 Intent
     * 指向目录逃逸或空名称。
     */
    internal fun safeShareFileName(fileName: String?): String? {
        if (fileName == null || fileName.isEmpty() || fileName.length > 255) return null
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains("..")) {
            return null
        }
        return fileName
    }

    private fun forward(packageName: String, reporter: Reporter) {
        val context = systemContext() ?: run {
            reporter.report { "system context unavailable package=$packageName" }
            return
        }
        val app = runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
        }.getOrNull() ?: run {
            reporter.report { "package unavailable package=$packageName" }
            return
        }
        reporter.report { "apk resolved package=$packageName" }
        val main = openReadOnly(app.sourceDir) ?: run {
            reporter.report { "main APK unavailable package=$packageName" }
            return
        }
        val splitPaths = app.splitSourceDirs.orEmpty().filterNotNull()
        if (splitPaths.size > LauncherShareBridge.MAX_SPLITS) {
            main.close()
            reporter.report { "too many splits package=$packageName" }
            return
        }
        val splits = ArrayList<ParcelFileDescriptor>()
        try {
            for (path in splitPaths) {
                val fd = openReadOnly(path) ?: run {
                    reporter.report { "split unavailable package=$packageName" }
                    return
                }
                splits += fd
            }
            val labels = LauncherShareBridge.splitLabels(app.splitNames)
            val extras = Bundle().apply {
                putInt("protocol", LauncherShareBridge.PROTOCOL)
                putString("package", packageName)
                putParcelable("main", main)
                putParcelableArrayList("splits", splits)
                putStringArrayList("labels", ArrayList(labels))
            }
            val result = context.contentResolver.call(
                Uri.parse("content://${LauncherShareBridge.CONTROL_AUTHORITY}"),
                LauncherShareBridge.METHOD_SHARE,
                null,
                extras,
            )
            val accepted = result != null && result.getBoolean("accepted", false)
            if (accepted) {
                reporter.report { "provider accepted package=$packageName" }
                launchChooser(packageName, result.getString("file"), reporter)
            } else {
                reporter.report { "module rejected package=$packageName" }
            }
        } catch (failure: Throwable) {
            reporter.report {
                "forward failed package=$packageName reason=${failure.javaClass.simpleName}"
            }
        } finally {
            runCatching { main.close() }
            splits.forEach { runCatching { it.close() } }
        }
    }

    private fun openReadOnly(path: String?): ParcelFileDescriptor? {
        if (path.isNullOrEmpty()) return null
        return runCatching {
            ParcelFileDescriptor.open(java.io.File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    private fun systemContext(): Context? = Reflect.attempt {
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        threadClass.getMethod("getSystemContext").invoke(thread) as Context
    }

    /**
     * 模块 Provider 只复制文件，不在这里直接启动 Activity。system_server 通过
     * 模块的可见 Activity 承接一次性请求，避免把后台线程的 startActivity 静默拦掉。
     */
    private fun launchChooser(packageName: String, fileName: String?, reporter: Reporter) {
        val context = moduleContext() ?: run {
            reporter.report { "module context unavailable package=$packageName" }
            return
        }
        val intent = fileName?.let(::launchIntent) ?: run {
            reporter.report { "module returned no file package=$packageName" }
            return
        }
        // system_server 不是模块文件 Provider 的 URI 所有者，不能替模块授予自身
        // 权限；Activity 与 Provider 同 UID，读取由同进程直接完成。Intent 上的
        // 读授权标志仍会传给 chooser 的接收方。
        runCatching { context.startActivity(intent) }
            .onSuccess { reporter.report { "activity launched package=$packageName" } }
            .onFailure {
                reporter.report {
                    "module launch failed package=$packageName reason=${it.javaClass.simpleName}"
                }
            }
    }

    private fun moduleContext(): Context? = runCatching {
        systemContext()?.createPackageContext(
            LauncherShareBridge.MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
    }.getOrNull()
}
