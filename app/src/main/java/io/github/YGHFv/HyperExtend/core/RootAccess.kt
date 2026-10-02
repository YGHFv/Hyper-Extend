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

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次 root 检测的结果。
 *
 * 四种情况在界面上是四句不同的话，**不做「有 / 没有」的二值化**：它们的下一步动作完全不同 ——
 * 有的要去 root 管理器点授权，有的是这台设备根本没有 root（那就别把功能设计成必须 root），
 * 还有的是 su 在但被 SELinux 之类挡了（去改策略，不是去点授权）。
 * 每一种都自带一句 [detail]，界面直接显示，不再自己拼文案。
 */
sealed interface RootStatus {
    /** `su -c id` 以 uid=0 返回。这才是真正可用的 root。 */
    data class Granted(val detail: String) : RootStatus

    /** su 在，但这次没能拿到 root（用户拒绝、授权框还停在屏幕上、或超时）。 */
    data class Denied(val detail: String) : RootStatus

    /** 候选路径里一个 su 都没有：设备没有 root，或本应用被 root 管理器隐藏了。 */
    data class NoSu(val detail: String) : RootStatus

    /** su 能起，但结果无法判定（异常、退出码与输出互相矛盾）。 */
    data class Failed(val detail: String) : RootStatus
}

/**
 * root 的判定与 su 的定位。
 *
 * ## 为什么单独成一个类
 *
 * 「让改动生效」（`HotReloader` / `ProcessRestarter`）是**动作**，而「这台机器上有没有 root」
 * 是它的**前提**。前提的判定有两个成本差好几个数量级的档位：
 *
 * - [suPath] / [hasSuBinary] / [quickHint]：只看几个固定路径存不存在。不弹窗、不阻塞，
 *   可以在界面组合期直接调用；
 * - [probe]：真的跑一次 `su -c id`。可能弹出授权框，可能阻塞二十秒 —— 只能在后台线程上跑。
 *
 * 界面必须**先不打扰用户**地把状态显示出来（[suPath]），只在用户明确点「检测」时才升级到
 * [probe]。所以这里给出两个档位，而不是一个 `isRooted()`。
 *
 * ## su 的候选路径为什么只有这一处
 *
 * `ProcessRestarter` 起进程时要用同一个解析结果（[suCommand]）。两处各写一份候选表，
 * 换 root 方案（Magisk → KernelSU → APatch）或升级系统时必然漏改一处，
 * 表现为「设置页说 root 可用、应用改动却报没有 root」这种自相矛盾的状态。
 */
object RootAccess {

    /**
     * su 可能出现的位置。
     *
     * 不能只用 `"su"` 让系统走 PATH 查找：Android 应用进程的 PATH 通常只有
     * `/product/bin` 和 `/system/bin`，而各 root 方案是把 su 提供在别处的 ——
     * Magisk 与 KernelSU 默认挂在 `/system/bin/su`（KernelSU 只给**已授权**的应用挂），
     * 老式方案在 `/system/xbin`，APatch 与 Magisk 还有各自的 `/data/adb` 路径。
     *
     * 这些路径在**未授权的进程里根本不可见**（`File.exists()` 返回 false），
     * 所以「一个都找不到」正是「没有 root 或被拒绝」这个结论的可靠依据。
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    /**
     * su 授权弹窗可能一直摆在那里等用户，而 `su -c` 会一直阻塞。
     * 20 秒足够用户点一次授权，又不至于让界面按钮转圈转到用户以为卡死。
     */
    private const val TIMEOUT_SECONDS = 20L

    /**
     * 已经确认可用的检测结果。
     *
     * 只缓存**成功**：拿到过 root 说明设备确实有 root，反复检测只是反复打扰用户。
     * 失败不缓存 —— 用户很可能只是刚才没点那个授权框，下一次动作时应该重新问一遍。
     */
    @Volatile
    private var granted: RootStatus.Granted? = null

    /**
     * 解析出的 su 路径；一个候选都不存在时返回 null。
     *
     * **不产生任何副作用**（不起进程、不弹窗），所以可以在界面组合期调用 ——
     * 打开设置页就能把「这台设备没有 root，应用改动用不了」说清楚，
     * 而不是等用户点了按钮才去报错。
     */
    fun suPath(): String? = SU_CANDIDATES.firstOrNull { path ->
        runCatching { val file = File(path); file.exists() && file.canExecute() }
            .getOrDefault(false)
    }

    /** [suPath] 的布尔形式。只证明「可能可用」，真正算不算数要用 [probe]。 */
    fun hasSuBinary(): Boolean = suPath() != null

    /**
     * 起进程用的 su 命令。
     *
     * 候选路径一个都不存在时仍然退回 `"su"` 交给 PATH —— 有的方案确实把它注入了环境，
     * 直接放弃会漏掉这些设备；真起不来时 `ProcessBuilder` 会抛 IOException，
     * 调用方会把它转成「没有 root 或未授权」的提示。
     */
    fun suCommand(): String {
        val path = suPath() ?: run {
            ModuleLog.warn("no su at known paths, falling back to PATH lookup")
            return "su"
        }
        return path
    }

    /**
     * 还没检测过时给用户看的一句话。
     *
     * 它必须同时回答两件事：「现在这个状态是什么意思」和「要不要做点什么」。
     * 找不到 su 时要说清楚**哪些功能不受影响** —— 否则用户会以为整个模块都不能用。
     */
    fun quickHint(): String = if (hasSuBinary()) {
        "已找到 su，但能不能用要等一次实际调用才能确定（首次会弹出授权请求）。"
    } else {
        "没有找到 su。这台设备看起来没有 root：功能开关照常保存与生效，" +
            "只有「让改动生效」这一步走不通（那条路需要重载或结束宿主进程）。"
    }

    /**
     * 完整检测：跑一次 `su -c id`，看它是否以 uid=0 返回。
     *
     * **必须在后台线程调用** —— su 会弹授权框并阻塞等待，在主线程上跑等于界面冻住。
     */
    @Synchronized
    fun probe(): RootStatus {
        granted?.let { return it }
        val result = runProbe(suCommand())
        if (result is RootStatus.Granted) granted = result
        ModuleLog.info("root probe: ${result::class.simpleName} (${describe(result)})")
        return result
    }

    /**
     * 用 root 读一个**别的应用私有目录里的**文件，返回原始字节。
     *
     * ## 为什么需要它
     *
     * 「读取钱包自带的卡面」这条路上的两个文件都在钱包自己的目录里
     * （见 `hook/feature/NfcCardFace` 的交回通道），而模块 App 与钱包是两个 uid，
     * 没有 root 时一个字节都读不到。root 是这里唯一不需要额外权限的通道 ——
     * 走 provider 需要注入侧先拿到一个 Context（在宿主进程里取 Context 要碰隐藏 API），
     * 走存储权限则需要模块申请一堆本来用不上的权限。
     *
     * ## 必须在后台线程调用
     *
     * 与 [probe] 同理：su 可能弹授权框并阻塞。这里额外起一个看门狗线程，
     * 因为**读数据不能像探针那样「等进程自己结束」** —— 输出可能大于管道缓冲，
     * 子进程会先写满缓冲再等我们读，双方互等就成了死锁。所以是「边读边等」+ 超时兜底。
     *
     * @return 读到且非空时返回字节；读不到、没 root、或超时都返回 null（只记日志，不抛）
     */
    fun catBytes(path: String, timeoutSeconds: Long = TIMEOUT_SECONDS): ByteArray? {
        val process = try {
            // 整个命令用单引号包住路径：路径里出现空格或 shell 元字符时不会被解释。
            ProcessBuilder(suCommand(), "-c", "cat '$path'").start()
        } catch (t: Throwable) {
            ModuleLog.warn("catBytes: cannot start su (${t.javaClass.simpleName})")
            return null
        }
        runCatching { process.outputStream.close() }

        // 看门狗：到点还没结束就强杀，避免授权框摆在那里时这里永远挂着。
        // 注意 stderr **不能**并进 stdout（见下面的进程构造）：那会把错误文本混进图片字节里。
        val watchdog = Thread {
            runCatching {
                Thread.sleep(timeoutSeconds * 1000)
                process.destroyForcibly()
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        val bytes = runCatching { process.inputStream.use { it.readBytes() } }.getOrNull()
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (code != 0 || bytes == null || bytes.isEmpty()) {
            ModuleLog.warn("catBytes($path) failed: exit=$code bytes=${bytes?.size ?: 0}")
            return null
        }
        // 这里成功等价于「su 确实能用」，顺手把结果记进 [probe] 的那份缓存，
        // 于是设置页不必再单独探一次。
        granted = RootStatus.Granted("${suCommand()} 已授权")
        return bytes
    }

    /** [catBytes] 的文本形式（UTF-8）。读不到返回 null。 */
    fun catText(path: String, timeoutSeconds: Long = TIMEOUT_SECONDS): String? =
        catBytes(path, timeoutSeconds)?.toString(Charsets.UTF_8)

    /**
     * 以 root 跑一条命令，返回它的标准输出。
     *
     * ## 与 [catBytes] 的区别
     *
     * [catBytes] 专为「读一个文件」而写，且刻意把 stderr 与 stdout 分开（否则错误文本会混进图片字节里）。
     * 这里要跑的是一般命令（`settings get/put`），同样不合并 stderr —— 成功时输出就是命令自己的结果，
     * 失败时以非 0 退出，由返回的 null 表达。**必须在后台线程调用**，理由同 [probe]。
     *
     * @return 命令以 0 退出时返回 stdout（可能为空串）；未拿到 root / 超时 / 非 0 退出返回 null
     */
    fun exec(command: String, timeoutSeconds: Long = TIMEOUT_SECONDS): String? {
        val process = try {
            ProcessBuilder(suCommand(), "-c", command).start()
        } catch (t: Throwable) {
            ModuleLog.warn("exec: cannot start su (${t.javaClass.simpleName})")
            return null
        }
        runCatching { process.outputStream.close() }

        val watchdog = Thread {
            runCatching {
                Thread.sleep(timeoutSeconds * 1000)
                process.destroyForcibly()
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        val text = runCatching { process.inputStream.bufferedReader().readText() }.getOrNull()
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        if (code != 0 || text == null) {
            ModuleLog.warn("exec failed (exit=$code): $command")
            return null
        }
        granted = RootStatus.Granted("${suCommand()} 已授权")
        return text
    }

    private fun describe(status: RootStatus): String = when (status) {
        is RootStatus.Granted -> status.detail
        is RootStatus.Denied -> status.detail
        is RootStatus.NoSu -> status.detail
        is RootStatus.Failed -> status.detail
    }

    private fun runProbe(su: String): RootStatus {        val process = try {
            ProcessBuilder(su, "-c", "id").redirectErrorStream(true).start()
        } catch (t: Throwable) {
            // 起不来只有两种可能，分开说：要么本来就没有 su，要么有但被挡住了。
            return if (hasSuBinary()) {
                RootStatus.Failed("su 存在但无法执行：${t.javaClass.simpleName}")
            } else {
                RootStatus.NoSu("找不到 su（已尝试 ${SU_CANDIDATES.size} 个路径）")
            }
        }
        // 关掉 stdin：su 若在等待输入会立刻拿到 EOF，而不是一直挂着。
        runCatching { process.outputStream.close() }

        val finished = runCatching { process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            process.destroyForcibly()
            return RootStatus.Denied(
                "等待 su 超过 ${TIMEOUT_SECONDS} 秒。若屏幕上弹出了 root 授权请求，" +
                    "先允许它，然后再检测一次。",
            )
        }

        // `id` 的输出只有几十字节，远小于管道缓冲，所以等待结束后再读不会死锁。
        val text = runCatching { process.inputStream.bufferedReader().readText() }
            .getOrDefault("").trim()
        val code = runCatching { process.exitValue() }.getOrDefault(-1)

        return when {
            text.contains("uid=0") -> RootStatus.Granted("$su 已授权，可以重载 / 结束宿主进程。")

            code == 0 && text.isEmpty() -> RootStatus.Failed(
                "su 以 0 退出但没有任何输出，无法判定是否拿到 root。",
            )

            else -> RootStatus.Denied(
                "su 退出码 $code，没有拿到 root。请在 root 管理器（Magisk / KernelSU / APatch）" +
                    "里给「澎湃补全计划」授权后重试。" +
                    if (text.isEmpty()) "" else "输出：${text.take(120)}",
            )
        }
    }
}
