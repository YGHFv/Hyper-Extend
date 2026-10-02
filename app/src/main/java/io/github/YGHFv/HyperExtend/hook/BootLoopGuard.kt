/*
 * Copyright (C) 2026 YGHFv
 *
 * 本文件是「澎湃补全计划」（HyperExtend）的一部分。
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证（由自由软件基金会发布）
 * 的条款重新发布和/或修改它，无论是许可证的第 3 版，还是由你选择）任何更新的版本。
 *
 * 本程序基于「希望它有用」而分发，但不提供任何担保；甚至不包括对适销性或特定用途
 * 适用性的默示担保。详见 GNU Affero 通用公共许可证。
 */

package io.github.YGHFv.HyperExtend.hook

import io.github.YGHFv.HyperExtend.core.FRAMEWORK_FUSE_RESET_KEY
import io.github.YGHFv.HyperExtend.core.ModuleLog
import java.io.File

/**
 * system_server / SystemUI 的框架 Hook 自保护。
 *
 * 每次 system_server 启动时记录一次启动事件。连续三次重启事件都落在五分钟窗口内时，
 * 写入持久化熔断标记，并同步写入系统属性，让之后启动的 SystemUI 也跳过全部框架 Hook。
 * 熔断是持久的；模块界面可以发起一次性解除请求，解除当前熔断并把计数归零，之后的
 * 重启仍然会继续计数，达到阈值时再次触发。
 *
 * 这个类在 system_server 的类加载早期运行，因此状态判定只依赖 `java.io`、`java.lang` 和
 * 反射读取系统属性；它不直接引用 Android API 类型。
 */
internal object BootLoopGuard {

    /** system_server 可读写；SystemUI 通过 DISABLED_PROPERTY 获取同一状态。 */
    private const val STATE_FILE = "/data/system/hyperextend_bootguard"

    /** 连续启动事件必须落在这个窗口内。 */
    private const val RAPID_WINDOW_MS = 5 * 60 * 1000L

    /** 三次重启事件触发熔断；首次启动不作为重启计数。 */
    private const val MAX_RAPID_RESTARTS = 3

    /** SystemUI 无法可靠读取 `/data/system`，所以用持久化属性广播熔断状态。 */
    private const val DISABLED_PROPERTY = "persist.sys.hyperextend.framework_disabled"

    /** 墙钟尚未校准时不进行计数。 */
    private const val MIN_PLAUSIBLE_TIME = 1_600_000_000_000L

    private data class State(
        val lastStartAt: Long = 0L,
        val rapidRestarts: Int = 0,
        val disabled: Boolean = false,
        val lastResetToken: Long = 0L,
    )

    /**
     * system_server 启动或热重载路径调用一次。
     * [countRestart] 只有真实启动路径才传 true，避免热重载被误计为重启。
     * 状态读写失败也按 true 处理：框架功能少装一次比再次触发开机循环更安全。
     */
    @Synchronized
    fun shouldSkipSystemServerHooks(settings: HookSettings, countRestart: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now < MIN_PLAUSIBLE_TIME) {
            ModuleLog.error("framework hook fuse: wall clock not ready ($now) — hooks skipped")
            return true
        }

        val previous = readState() ?: run {
            ModuleLog.error("framework hook fuse: state unreadable — system_server hooks skipped")
            return true
        }
        val resetToken = settings.long(FRAMEWORK_FUSE_RESET_KEY)
        if (resetToken > previous.lastResetToken) {
            val reset = State(
                lastStartAt = now,
                rapidRestarts = 0,
                disabled = false,
                lastResetToken = resetToken,
            )
            if (!disengageProperty()) {
                ModuleLog.error("framework hook fuse: cannot clear disable property — hooks skipped")
                return true
            }
            if (!writeState(reset)) {
                // 属性已经清掉但状态未确认，立即重新合闸，下一次点击仍可重试。
                engageProperty()
                ModuleLog.error("framework hook fuse: cannot persist manual reset — hooks skipped")
                return true
            }
            ModuleLog.info(
                "framework hook fuse manually released: token=$resetToken; " +
                    "restart counter reset and automatic protection remains enabled",
            )
            return false
        }

        if (readDisabledProperty() != false) {
            ModuleLog.warn("framework hook fuse is engaged — system_server hooks skipped")
            return true
        }
        if (previous.disabled) {
            engageProperty()
            ModuleLog.warn("framework hook fuse is engaged — system_server hooks skipped")
            return true
        }
        if (!countRestart) return false

        val rapidRestarts = if (
            previous.lastStartAt > 0L &&
            now >= previous.lastStartAt &&
            now - previous.lastStartAt <= RAPID_WINDOW_MS
        ) {
            previous.rapidRestarts + 1
        } else {
            0
        }
        val next = State(
            lastStartAt = now,
            rapidRestarts = rapidRestarts,
            disabled = rapidRestarts >= MAX_RAPID_RESTARTS,
            lastResetToken = previous.lastResetToken,
        )
        if (!writeState(next)) {
            ModuleLog.error("framework hook fuse: state unwritable — system_server hooks skipped")
            return true
        }

        if (next.disabled) {
            engageProperty()
            ModuleLog.error(
                "FRAMEWORK HOOK FUSE ENGAGED: $rapidRestarts restarts within " +
                    "${RAPID_WINDOW_MS / 60_000}s — system_server and SystemUI hooks disabled. " +
                    "Clear $DISABLED_PROPERTY and request a manual reset from the module.",
            )
            return true
        }
        return false
    }

    /** SystemUI 通过系统属性读取同一份持久化熔断状态。 */
    fun shouldSkipSystemUiHooks(): Boolean {
        if (readDisabledProperty() != false) {
            ModuleLog.warn("framework hook fuse is engaged — SystemUI hooks skipped")
            return true
        }
        return false
    }

    private fun readState(): State? = try {
        val file = File(STATE_FILE)
        if (!file.exists()) {
            State()
        } else {
            val parts = file.readText().trim().split(',')
            val lastStartAt = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val rapidRestarts = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val disabled = when (parts.getOrNull(2)) {
                null, "0" -> false
                "1" -> true
                else -> return null
            }
            val lastResetToken = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
            State(lastStartAt, rapidRestarts, disabled, lastResetToken)
        }
    } catch (t: Throwable) {
        ModuleLog.error("framework hook fuse: cannot read $STATE_FILE", t)
        null
    }

    private fun writeState(state: State): Boolean = try {
        val file = File(STATE_FILE)
        file.parentFile?.mkdirs()
        file.writeText(
            "${state.lastStartAt},${state.rapidRestarts}," +
                "${if (state.disabled) 1 else 0},${state.lastResetToken}",
        )
        true
    } catch (t: Throwable) {
        ModuleLog.error("framework hook fuse: cannot write $STATE_FILE", t)
        false
    }

    private fun readDisabledProperty(): Boolean? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, DISABLED_PROPERTY) as? String)?.trim() == "1"
    } catch (t: Throwable) {
        ModuleLog.error("framework hook fuse: cannot read $DISABLED_PROPERTY", t)
        null
    }

    private fun engageProperty(): Boolean = setProperty("1")

    private fun disengageProperty(): Boolean = setProperty("0")

    private fun setProperty(value: String): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val set = clazz.getMethod("set", String::class.java, String::class.java)
        set.invoke(null, DISABLED_PROPERTY, value)
        true
    }.getOrElse {
        ModuleLog.error("framework hook fuse: cannot set $DISABLED_PROPERTY=$value", it)
        false
    }
}