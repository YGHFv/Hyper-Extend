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

/** Persistent, independent startup fuses for system_server and SystemUI. */
internal object BootLoopGuard {
    private const val STATE_FILE = "/data/system/hyperextend_bootguard"
    private const val DISABLED_PROPERTY = "persist.sys.hyperextend.framework_disabled"
    private val serverStore = BootFuseStore(File(STATE_FILE))
    private var systemUiDecision: Boolean? = null

    @Synchronized
    fun shouldSkipSystemServerHooks(settings: HookSettings, countRestart: Boolean): Boolean {
        val previous = serverStore.read()
        val next = BootFusePolicy.next(previous, System.currentTimeMillis(), settings.long(FRAMEWORK_FUSE_RESET_KEY), countRestart)
        if (next == null) {
            ModuleLog.error("framework hook fuse: unreadable state or unsafe clock; hooks skipped")
            engageProperty()
            return true
        }
        val reset = previous != null && next.lastResetToken > previous.lastResetToken
        if (reset) {
            // Persist first; roll the token back if the property cannot be cleared.
            if (!serverStore.write(next)) {
                engageProperty()
                ModuleLog.error("framework hook fuse: manual reset failed; hooks skipped")
                return true
            }
            if (!setProperty("0")) {
                engageProperty()
                if (previous != null) serverStore.write(previous)
                ModuleLog.error("framework hook fuse: cannot clear property; hooks skipped")
                return true
            }
            ModuleLog.info("framework hook fuse reset; automatic protection remains enabled")
            return false
        }
        if (readDisabledProperty() != false || next.disabled) {
            engageProperty()
            if (next.disabled) serverStore.write(next)
            ModuleLog.warn("framework hook fuse engaged; system_server hooks skipped")
            return true
        }
        if (countRestart && !serverStore.write(next)) {
            engageProperty()
            ModuleLog.error("framework hook fuse: cannot persist start; hooks skipped")
            return true
        }
        return false
    }

    @Synchronized
    fun shouldSkipSystemUiHooks(settings: HookSettings, dataDir: String?): Boolean {
        systemUiDecision?.let { return it }
        val skip = checkSystemUi(settings, dataDir)
        systemUiDecision = skip
        return skip
    }

    private fun checkSystemUi(settings: HookSettings, dataDir: String?): Boolean {
        if (readDisabledProperty() != false) {
            ModuleLog.warn("framework hook fuse engaged; SystemUI hooks skipped")
            return true
        }
        if (dataDir.isNullOrBlank()) {
            ModuleLog.error("SystemUI fuse: no private data directory; hooks skipped")
            return true
        }
        val store = BootFuseStore(File(dataDir, "files/hyperextend_systemui_bootguard"))
        val previous = store.read()
        val next = BootFusePolicy.next(previous, System.currentTimeMillis(), settings.long(FRAMEWORK_FUSE_RESET_KEY), true)
        if (next == null || !store.write(next)) {
            ModuleLog.error("SystemUI fuse: unsafe clock or state IO failed; hooks skipped")
            return true
        }
        if (next.disabled) {
            ModuleLog.error("SYSTEMUI HOOK FUSE ENGAGED: three restarts in five minutes; hooks skipped until manual reset")
            return true
        }
        ModuleLog.info("SystemUI fuse ready: rapidRestarts=${next.rapidRestarts}; automatic protection enabled")
        return false
    }

    private fun readDisabledProperty(): Boolean? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val value = clazz.getMethod("get", String::class.java).invoke(null, DISABLED_PROPERTY) as? String
        when (value?.trim()) { "", "0" -> false; "1" -> true; else -> null }
    } catch (t: Throwable) {
        ModuleLog.error("framework hook fuse: cannot read $DISABLED_PROPERTY", t)
        null
    }

    private fun engageProperty(): Boolean = setProperty("1")

    private fun setProperty(value: String): Boolean = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("set", String::class.java, String::class.java).invoke(null, DISABLED_PROPERTY, value)
        readDisabledProperty() == (value == "1")
    }.getOrElse {
        ModuleLog.error("framework hook fuse: cannot set $DISABLED_PROPERTY=$value", it)
        false
    }
}
