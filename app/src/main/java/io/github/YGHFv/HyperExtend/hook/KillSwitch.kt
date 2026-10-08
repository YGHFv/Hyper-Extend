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

package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.core.ModuleLog
import java.io.File

/**
 * 紧急停用闸（防砖逃生门）。
 *
 * ## 它要解决的那个最坏情况
 *
 * 本模块的作用域里有 `system`（system_server）。万一某一版的 hook 在 system_server 里
 * 真的把系统搞进了开机循环，用户会陷入一个死角：
 *
 * - 进不去系统 → 打不开模块界面 → 改不了开关；
 * - LSPosed Manager 也在系统里 → 同样打不开 → 关不掉模块。
 *
 * 剩下唯一还能用的通道就是**恢复模式 / adb**。所以这里提供两个「不需要开机即可生效」的标记，
 * 任何一个成立，全部 hook 一律不装：
 *
 * ```
 * # 方式一：属性（需要 root，重启后仍保留，因为用了 persist. 前缀）
 * adb shell setprop persist.sys.hyperextend.disabled 1
 *
 * # 方式二：文件（只需要能写其中一个路径）
 * adb shell touch /data/local/tmp/hyperextend.disabled
 * adb shell mkdir -p /sdcard/HyperExtend && adb shell touch /sdcard/HyperExtend/disable
 * ```
 *
 * 撤销就是把属性设回 0 / 删掉文件，**不需要卸载模块**。
 *
 * ## 为什么放在 hook 侧而不是界面侧
 *
 * 界面在模块 App 进程里，而模块 App 进不去系统时也一样打不开 —— 界面上放一个「紧急停用」
 * 按钮，恰恰在最需要它的时候是够不着的。真正能在死角里生效的必须是**文件系统/属性**
 * 这类外部可写的开关。
 *
 * ## 为什么结果要缓存
 *
 * 每个被注入进程只查一次：进程生命周期内这些标记不会变（用户要改它们，必然是在
 * 开机之前或重启之后动手）。缓存省掉的是 SystemUI 这种反复触发的路径上的重复 IO。
 */
internal object KillSwitch {

    /**
     * 用 `persist.` 前缀：普通属性重启即丢，而这个标记必须在「重启后仍然生效」——
     * 否则用户设完一重启，模块又装上了，等于没设。
     */
    private const val PROPERTY_NAME = "persist.sys.hyperextend.disabled"

    /**
     * 文件标记。
     *
     * - `/data/local/tmp/`：adb shell 默认可写，且在恢复模式挂载 /data 后同样可达。
     * - `/sdcard/HyperExtend/`：少数设备上 adb 的 shell 用户写不了 /data/local/tmp，
     *   但能写共享存储；多一条路就多一分救得回来的可能。
     */
    private val FLAG_FILES = listOf(
        "/data/local/tmp/hyperextend.disabled",
        "/sdcard/HyperExtend/disable",
    )

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var reported = false

    /**
     * 属性读取失败时跳过 Hook；文件路径不可访问时由属性逃生门兜底。
     */
    fun isEngaged(): Boolean {
        cached?.let { return it }
        return synchronized(this) {
            cached?.let { return it }
            val engaged = propertyFlag() || fileFlag()
            cached = engaged
            engaged
        }
    }

    /**
     * 查一次并（首次命中时）记一条日志。
     *
     * 调用点只需要判断返回值：true 表示本进程里**什么都不要装**。
     * 日志只打一次，避免 SystemUI 侧每次分派都刷屏。
     */
    fun reportOnce(): Boolean {
        val engaged = isEngaged()
        if (engaged && !reported) {
            reported = true
            ModuleLog.warn(
                "KILL SWITCH engaged — all hooks are disabled in this process. " +
                    "Clear $PROPERTY_NAME or delete ${FLAG_FILES.first()} to re-enable.",
            )
        }
        return engaged
    }

    private fun propertyFlag(): Boolean = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        val value = get.invoke(null, PROPERTY_NAME) as? String
        value?.trim() !in listOf("", "0")
    } catch (failure: Throwable) {
        ModuleLog.error("kill switch property unreadable; hooks skipped", failure)
        true
    }

    private fun fileFlag(): Boolean = FLAG_FILES.any { path ->
        try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }
}
