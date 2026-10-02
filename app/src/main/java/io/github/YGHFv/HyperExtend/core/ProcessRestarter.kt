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

import java.util.concurrent.TimeUnit

/** 一次重启操作的结果。成功与失败都带一句**能直接显示给用户的话**。 */
sealed interface RestartResult {
    data class Done(val message: String) : RestartResult
    data class Failed(val message: String) : RestartResult
}

/**
 * 重启一个作用域宿主进程。
 *
 * ## 为什么需要它
 *
 * 开关的值是在**进程启动时**被读进内存的（注入侧的 `HookSettings` 只在两个进程启动回调里查表）。
 * 改完开关不重启宿主，用户看到的现象就是「拨了没反应」，然后会去反复拨、反复重启设备 ——
 * 这一页存在的意义就是把这个来回压缩成一次点击。
 *
 * ## 为什么走 su
 *
 * 结束别人的进程需要 `KILL_ANY_PROCESS` 级别的权限，普通应用拿不到（`am force-stop` 同理，
 * 它要 `FORCE_STOP_PACKAGES`）。本模块又刻意不申请任何运行时权限（见 AndroidManifest 注释），
 * 所以唯一的路是 root。设备既然跑得动 LSPosed，root 本来就在，只是授权与否由用户决定。
 *
 * ## 失败必须说清楚是哪一种失败
 *
 * 「没弹授权窗」「弹了但你拒绝了」「命令跑了但没找到进程」在用户眼里都是「点了没反应」，
 * 但处理方式完全不同。所以这里把每一个分支都区分出来并给出对应的话，不做统一兜底文案。
 *
 * ## 它是兜底，首选是热重载
 *
 * 结束进程是任何框架版本都能用的做法，但它有一个**可见的副作用**：SystemUI 一死，状态栏窗口
 * 就跟着消失，而本模块的界面是边到边的（内容铺满整屏），于是那一瞬间界面会整块顶到状态栏的
 * 位置上 —— 用户看到的就是「重启的一瞬间软件跳到了状态栏」。
 *
 * 所以界面的顺序是「先试热重载（见 [HotReloader]），走不通才落到这里」。本类只负责把
 * 「落下来」这一步做扎实：结束进程的机制不该因为上面多了一条路而变得含糊。
 */
object ProcessRestarter {

    /**
     * su 授权弹窗可能一直摆在那里等用户，而 `su -c` 会一直阻塞。
     * 20 秒足够用户点一次授权，又不至于让界面按钮转圈转到用户以为卡死。
     */
    private const val TIMEOUT_SECONDS = 20L

    /** su 不可用时的统一文案。用户在这里要做的动作很具体：去 root 管理器里授权。 */
    private const val NO_SU_HINT =
        "没能执行 su：要么这台设备没有 root，要么本应用还没有被授权。" +
            "请在 root 管理器（Magisk / KernelSU / APatch）里给「澎湃补全计划」授权后重试。"

    /**
     * 起手先做一次**不产生副作用**的检查（[RootAccess.hasSuBinary]，只看文件在不在）。
     *
     * 连 su 文件都找不到时，起一个注定失败的进程除了多一次 `IOException` 没有任何价值，
     * 而那句异常与「有没有 root」毫无关系 —— 直接给用户一句能照着做的话。
     */
    private fun suMissing(): RestartResult? =
        if (RootAccess.hasSuBinary()) null else RestartResult.Failed(NO_SU_HINT)

    /**
     * 重启 [scope] 对应的进程。
     *
     * **必须在后台线程调用**：内部会阻塞等待外部进程。
     */
    fun restart(scope: HyperScope): RestartResult {
        suMissing()?.let { return it }
        val script = when (scope.restartKind) {
            RestartKind.KILL -> killScript(scope.process)
            RestartKind.FORCE_STOP -> "am force-stop ${scope.process}; echo stopped"
            // 设备重启不在这里做：它不是「重启一个进程」，而是要用户明确点第二次确认。
            // 把这个判断留在 UI 层，本函数就永远只做「结束某个进程」这一件可预期的事。
            RestartKind.REBOOT -> return RestartResult.Failed(
                "系统服务无法单独结束进程，只能重启设备（右上角在热重载走不通时会给出该选项）。",
            )
        }

        val su = RootAccess.suCommand()
        ModuleLog.info("restart ${scope.id}: $su -c $script")

        val process = try {
            ProcessBuilder(su, "-c", script).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            ModuleLog.error("restart ${scope.id}: cannot start su ($su)", t)
            return RestartResult.Failed(NO_SU_HINT)
        }

        // 关掉 stdin：su 若在等待输入会立刻拿到 EOF，而不是一直挂着。
        runCatching { process.outputStream.close() }

        // 必须先起读取线程再 waitFor：命令输出的量不可控，管道写满时子进程会卡死，
        // 而 waitFor 又一直等子进程结束 —— 两边互等，表现为「永久转圈」。
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }.apply {
            isDaemon = true
            name = "hyperextend-restart-reader"
            start()
        }

        val finished = runCatching { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)

        if (!finished) {
            process.destroyForcibly()
            ModuleLog.warn("restart ${scope.id}: timeout after ${TIMEOUT_SECONDS}s")
            return RestartResult.Failed(
                "等待 su 超时。若屏幕上弹出了 root 授权请求，请先允许本应用使用 root，" +
                    "然后回到这里重试。",
            )
        }

        runCatching { reader.join(1000) }
        val text = output.toString().trim()
        val code = runCatching { process.exitValue() }.getOrDefault(-1)

        ModuleLog.info("restart ${scope.id}: exit=$code output=${text.ifEmpty { "(empty)" }}")

        return when (scope.restartKind) {
            RestartKind.KILL -> when {
                text.contains("killed:") -> RestartResult.Done(
                    "${scope.title} 已结束，系统会立刻把它重新拉起来，新的设置即刻生效。",
                )

                text.contains("not-running") -> RestartResult.Done(
                    "${scope.title} 当前没有在运行，无需重启 —— 它下次启动时就会读到新设置。",
                )

                else -> RestartResult.Failed(
                    "命令已执行但结果不明（退出码 $code）。${describeOutput(text)}",
                )
            }

            RestartKind.FORCE_STOP -> if (code == 0) {
                RestartResult.Done(
                    "${scope.title} 已结束，下次打开它时新的设置会生效。",
                )
            } else {
                RestartResult.Failed(
                    "结束 ${scope.title} 失败（退出码 $code）。" +
                        "多见于 root 授权被拒绝。${describeOutput(text)}",
                )
            }

            RestartKind.REBOOT -> RestartResult.Failed("内部错误：设备重启不应走到这里。")
        }
    }

    /**
     * 重启设备。**调用方必须已经拿到用户的明确确认**（这是全模块唯一的整机级操作）。
     */
    fun reboot(): RestartResult {
        ModuleLog.warn("reboot requested by user")
        return runSuCommand("reboot", "重启命令已下发，设备即将重启。")
    }

    /**
     * 结束一个进程的命令行。
     *
     * 用 `pidof` 自取 pid 而不是 `killall`：`killall` 在部分精简 ROM 的 toybox 里没有，
     * 而 `pidof` 是 toybox 的标准命令。整段写成一行（`;` 分隔）是因为 `su -c` 收到的
     * 多行脚本在个别 superuser 实现里会被截断到第一行。
     *
     * `not-running` 分支不是错误：SystemUI 偶尔会因为崩溃正处于被重新拉起的间隙，
     * 此时命令必须仍然以 0 退出，否则界面会报一个并不存在的失败。
     */
    private fun killScript(process: String): String {
        // Kotlin 里 `$d` 后面紧跟字母会被解析成另一个变量名（`$dpids`），
        // 所以每处都用 `${d}` 明确界定，不用字符串替换去补救 —— 那种写法读的人得在脑子里跑一遍。
        val d = '$'
        return "pids=${d}(pidof $process); " +
            "if [ -n \"${d}pids\" ]; then kill -9 ${d}pids; echo killed:${d}pids; " +
            "else echo not-running; fi"
    }

    private fun runSuCommand(command: String, successMessage: String): RestartResult {
        suMissing()?.let { return it }
        val su = RootAccess.suCommand()
        val process = try {
            ProcessBuilder(su, "-c", command).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            ModuleLog.error("su command failed ($su): $command", t)
            return RestartResult.Failed(NO_SU_HINT)
        }
        runCatching { process.outputStream.close() }
        val finished = runCatching { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return RestartResult.Failed("等待 su 超时，操作可能未执行。")
        }
        return RestartResult.Done(successMessage)
    }

    /** 把命令输出压成一句能放进界面的短话。空输出返回空串，调用方会自然拼掉。 */
    private fun describeOutput(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return when {
            oneLine.isEmpty() -> ""
            oneLine.length <= 120 -> "输出：$oneLine"
            else -> "输出：${oneLine.take(120)}…"
        }
    }
}
