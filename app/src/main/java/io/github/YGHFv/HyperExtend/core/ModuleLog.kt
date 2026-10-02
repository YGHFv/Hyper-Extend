/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是（由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.core

import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块日志。
 *
 * 两件事必须同时成立，所以这个类不能直接用 `android.util.Log` 了事：
 *
 * 1. **被注入的进程里要能看到**——宿主（SystemUI / 设置 / system_server）进程的 logcat
 *    是用户唯一能自查的地方，所以每条都要真的打到 logcat。
 * 2. **模块自己的界面里也要能看到**——模块常年不需要用户打开，一旦打开多半是「某个开关
 *    按了没反应」，此时要求用户去抓 logcat 等于劝退。于是同时存一份内存环形缓冲，
 *    界面里「日志」页直接翻。
 *
 * 这个类在**两个进程里各有一份**（模块 App 一份、每个被注入进程一份），缓冲互不相通。
 * 界面里显示的只是模块 App 自己那份，这是刻意的：被注入进程那份要跨进程搬运，代价远大于
 * 它带来的价值，而诊断注入侧问题本来就该看 logcat。
 *
 * 不持有任何 libxposed 类型：本类在两个进程里都会被加载，碰到 compileOnly 的类就是
 * NoClassDefFoundError。
 */
object ModuleLog {

    /** logcat 上统一用这个 tag 过滤：`adb logcat -s HyperExtend`。 */
    const val TAG = "HyperExtend"

    /** 环形缓冲容量。300 条足够覆盖一次完整开机后的 hook 安装过程，再多只是占内存。 */
    private const val CAPACITY = 300

    /** 时间戳格式非线程安全，加锁成本又高于一次格式化；用 ThreadLocal 各持一份。 */
    private val clockFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    }

    private val buffer = ArrayDeque<String>(CAPACITY)

    /**
     * 注入侧的文件日志（见 [attachFileSink]）。
     *
     * ## 为什么必须有它
     *
     * 实测部分澎湃设备上 logd 对 adb 是「冻结」的：`adb logcat` 永远只能读到开机那几秒的
     * 陈旧内容，之后所有进程（包括 shell 的 `log` 命令）写进 logd 的东西都到不了 logcat。
     * 那台设备上被注入进程的日志通过 logcat **一条都拿不到**，「没日志」从此不再等于
     * 「没发生」。所以注入侧的每条日志在进内存缓冲的同时追加写进宿主自己的私有目录，
     * 模块 App 用已有的 root 读取通道（`RootAccess.catText`）把它取回来显示。
     */
    @Volatile
    private var logFile: File? = null

    /** 只允许 attach 一次：重复 attach 会在文件里重复回放缓冲。 */
    @Volatile
    private var fileAttached = false

    private val fileLock = Any()

    /** 单文件上限。超过就在下次 attach 时重新开始，避免某个高频路径把它写成无穷大。 */
    private const val MAX_FILE_BYTES = 512 * 1024

    /** 已捕获的异常数（不含普通日志）。关于页用它提示「有异常待看」。 */
    @Volatile
    var errorCount: Int = 0
        private set

    fun info(message: String) {
        emit(Log.INFO, message, null)
    }

    fun debug(message: String) {
        emit(Log.DEBUG, message, null)
    }

    /**
     * 启动自检专用：带 `[entry]` 前缀。
     *
     * 存在的理由是日志检索 —— 「这个进程里模块到底加载了没有」是排查一切问题的第一步，
     * 而它在日志里只是一行普通 INFO，和后面几十行 hook 细节混在一起很难一眼找到。
     * 加个前缀就能 `grep entry` 直接看全貌。
     */
    fun entry(message: String) {
        emit(Log.INFO, "[entry] $message", null)
    }

    fun warn(message: String) {
        emit(Log.WARN, message, null)
    }

    fun error(message: String, throwable: Throwable? = null) {
        errorCount += 1
        emit(Log.ERROR, message + (throwable?.let { " | " + it.stackTraceToString() } ?: ""), throwable)
    }

    private fun emit(priority: Int, message: String, throwable: Throwable?) {
        val line = "${stamp()} ${levelTag(priority)} $message"
        synchronized(buffer) {
            if (buffer.size >= CAPACITY) buffer.removeFirst()
            buffer.addLast(line)
        }
        val file = logFile
        if (file != null) {
            synchronized(fileLock) {
                runCatching { file.appendText(line + "\n") }
            }
        }
        // logcat 这一路不能省：它仍是被注入进程日志的标准出口，只是不再唯一。
        runCatching {
            if (throwable != null) Log.println(priority, TAG, "$message\n${Log.getStackTraceString(throwable)}")
            else Log.println(priority, TAG, message)
        }
    }

    /**
     * 把日志镜像到 [path] 指向的文件。**只在被注入的进程里调用**：
     * 路径是宿主自己的私有目录（普通宿主是 `<dataDir>/files/`，system_server 是 `/data/system/`），
     * 调用方必须是那个进程（写别人的目录没有任何意义，也没有权限）。
     *
     * attach 发生在 `onModuleLoaded` 之后（那时才知道宿主是谁），所以把缓冲里已有的行
     * —— 包括最早那行 `[entry] module loaded` —— 回放进文件，时间线才完整。
     * 回放之后新日志实时追加。文件带一行会话头（pid + 回放条数），跨进程重启可分辨。
     */
    fun attachFileSink(path: String) {
        if (fileAttached) return
        fileAttached = true
        val file = runCatching {
            val f = File(path)
            if (f.length() > MAX_FILE_BYTES) f.delete()
            val parent = f.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                // 目录建不出来（多半是路径猜错了 uid 的地盘）：明确放弃，而不是留下一个
                // 每条日志都静默写失败的句柄 —— 那会让「文件不存在」变成无法归因的现象。
                return
            }
            f
        }.getOrNull() ?: return
        logFile = file
        val backlog = synchronized(buffer) { buffer.toList() }
        synchronized(fileLock) {
            runCatching {
                file.appendText("==== attach pid=${Process.myPid()} (${backlog.size} buffered) ====\n")
                backlog.forEach { file.appendText(it + "\n") }
            }
        }
    }

    /** 快照（最新的在最后）。界面直接倒序显示。 */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        errorCount = 0
    }

    private fun stamp(): String = clockFormat.get()!!.format(Date())

    private fun levelTag(priority: Int): String = when {
        priority >= Log.ERROR -> "E"
        priority >= Log.WARN -> "W"
        priority < Log.INFO -> "D"
        else -> "I"
    }
}
