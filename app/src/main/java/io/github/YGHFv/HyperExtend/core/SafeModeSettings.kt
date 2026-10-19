/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.Context
import android.os.Process
import android.os.SystemClock
import io.github.YGHFv.HyperExtend.BuildConfig
import io.github.YGHFv.HyperExtend.config.HyperSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SafeModeStatus(
    val scope: HyperScope,
    val disabled: Boolean,
    val incident: SafeModeIncident?,
    val acknowledged: Boolean,
    val detail: String? = null,
    val loading: Boolean = false,
    val nativeBlocked: Boolean = false,
)

/** 安全设置不参与功能备份和重置；桌面另合并原生后端的实际状态。 */
internal object SafeModeSettings {
    private val mutable = MutableStateFlow<List<SafeModeStatus>>(emptyList())
    val state = mutable.asStateFlow()
    private val nativeReadLock = Any()
    private var nativeStatus: LauncherSafetyStatus? = null
    private var lastNativeRead = 0L

    private fun nativeLauncher(scope: HyperScope): Boolean =
        BuildConfig.LAUNCHER_NATIVE_PROTOTYPE && scope.process == "com.miui.home"

    /** 只能在后台调用；失败保留未知/阻断状态，不伪造“安全模式已关闭”。 */
    fun refreshNative(context: Context, force: Boolean = false): LauncherSafetyStatus? = synchronized(nativeReadLock) {
        if (!BuildConfig.LAUNCHER_NATIVE_PROTOTYPE) return@synchronized null
        if (!force && nativeStatus != null && SystemClock.elapsedRealtime() - lastNativeRead < 1000) {
            return@synchronized nativeStatus
        }
        val observed = LauncherSafetyStatusReader.read(Process.myUid() / 100000)
        synchronized(this) {
            nativeStatus = observed
            lastNativeRead = SystemClock.elapsedRealtime()
            refresh(context)
        }
        observed
    }

    @Synchronized fun refresh(context: Context) {
        val prefs = HyperSettings.localPrefs(context)
        mutable.value = SCOPES.map { scope ->
            val native = nativeStatus.takeIf { nativeLauncher(scope) }
            val manual = prefs.getBoolean(SafeModeKeys.disabled(scope.id), false)
            val incident = native?.incidentId?.let { id ->
                SafeModeIncident(id, prefs.getLong(SafeModeKeys.reset(scope.id), 0),
                    native.state?.lastStartAt ?: 0, native.reason.orEmpty())
            } ?: SafeModeIncident.decode(prefs.getString(SafeModeKeys.incident(scope.id), "").orEmpty())
            val nativeBlocked = LauncherSafetyPolicy.masksModule(native)
            val nativeAttention = native?.needsManualAttention == true
            val reason = when {
                manual && nativeLauncher(scope) ->
                    "已手动停用桌面模块功能，原功能配置保留；不会改写原生启动保护状态。"
                nativeBlocked -> native?.reason ?: "桌面原生启动保护已锁存。"
                nativeAttention -> native?.reason
                nativeLauncher(scope) && native == null -> "正在读取桌面原生保护状态…"
                else -> native?.reason
            }
            SafeModeStatus(
                scope = scope,
                disabled = manual || incident != null || nativeBlocked || nativeAttention,
                incident = incident,
                acknowledged = incident == null ||
                    prefs.getString(SafeModeKeys.acknowledged(scope.id), "") == incident.id,
                detail = reason,
                loading = nativeLauncher(scope) && native == null,
                nativeBlocked = nativeBlocked,
            )
        }
    }

    fun setDisabled(context: Context, scope: HyperScope, disabled: Boolean): Boolean = runCatching {
        require(scope in SCOPES)
        val native = nativeLauncher(scope)
        // 关闭手动安全模式只撤销模块自己的停用标记，不能依赖原生库已经加载：
        // 否则安装过一次原生库后，下一次进程没有映射它就会让开关永久关不掉。
        val observedBefore = if (native && !disabled) refreshNative(context, force = true) else null
        val nativeBlocked = LauncherSafetyPolicy.masksModule(observedBefore)
        val nativeRecovery = LauncherSafetyPolicy.requiresNativeRecovery(observedBefore)
        if (native && LauncherSafetyPolicy.release(observedBefore) == LauncherManualRelease.BLOCKED_BY_RESIDUE) {
            ModuleLog.error("safe mode manual attention required: ${observedBefore?.reason.orEmpty()}")
            return@runCatching false
        }
        if (!disabled && !nativeBlocked) {
            persistDisabled(context, scope, false)
            check(HyperSettings.syncToFramework(context, synchronous = true)) {
                "安全设置未同步，开关保持原状态"
            }
            if (native) {
                val restarted = ProcessRestarter.restart(scope)
                check(restarted is RestartResult.Done) { (restarted as RestartResult.Failed).message }
            }
            if (native) refreshNative(context, force = true) else refresh(context)
            return@runCatching true
        }
        // 只有原生熔断确实锁存时，才要求读到完整状态并走恢复事务。
        check(!nativeBlocked || nativeRecovery) {
            "未读到完整的桌面原生保护状态，未把普通重启当作恢复；${observedBefore?.reason.orEmpty()}"
        }
        val before = observedBefore?.state
        persistDisabled(context, scope, disabled)
        val sent = HyperSettings.syncToFramework(context, synchronous = native)
        if (native) {
            check(sent) { "桌面安全设置未同步，未重启桌面" }
            val restarted = ProcessRestarter.restart(scope, recoverLauncher = nativeBlocked)
            check(restarted is RestartResult.Done) { (restarted as RestartResult.Failed).message }
            if (!disabled) {
                var restored = false
                for (attempt in 0 until 10) {
                    Thread.sleep(500)
                    val observed = refreshNative(context, force = true)
                    if (LauncherSafetyPolicy.recovered(observed, before)) {
                        restored = true
                        break
                    }
                }
                // 空功能恢复结束后恢复原设置投影，不把临时通道当成用户的新配置。
                HyperSettings.syncToFramework(context, synchronous = true)
                check(restored) { "桌面尚未确认恢复，保护状态保持显示；请查看原生状态或日志" }
            } else refreshNative(context, force = true)
        }
        true
    }.getOrElse {
        if (nativeLauncher(scope) && !disabled) HyperSettings.syncToFramework(context, synchronous = true)
        ModuleLog.error("safe mode setting failed", it)
        false
    }

    @Synchronized private fun persistDisabled(context: Context, scope: HyperScope, disabled: Boolean) {
        val prefs = HyperSettings.localPrefs(context)
        val editor = prefs.edit().putBoolean(SafeModeKeys.disabled(scope.id), disabled)
        if (!disabled) {
            val previous = maxOf(prefs.getLong(SafeModeKeys.reset(scope.id), 0), prefs.getLong(FRAMEWORK_FUSE_RESET_KEY, 0))
            editor.putLong(SafeModeKeys.reset(scope.id), SafeModeKeys.nextReset(previous, System.currentTimeMillis()))
                .remove(SafeModeKeys.incident(scope.id)).remove(SafeModeKeys.acknowledged(scope.id))
        }
        check(editor.commit())
        refresh(context)
    }

    @Synchronized fun accept(context: Context, scope: HyperScope, incident: SafeModeIncident): Boolean {
        val prefs = HyperSettings.localPrefs(context)
        val reset = maxOf(prefs.getLong(SafeModeKeys.reset(scope.id), 0), prefs.getLong(FRAMEWORK_FUSE_RESET_KEY, 0))
        // A queued old-process report cannot undo an explicit recovery request.
        if (!SafeModeReportPolicy.accepts(reset, incident)) return false
        val existing = SafeModeIncident.decode(prefs.getString(SafeModeKeys.incident(scope.id), "").orEmpty())
        if (SafeModeReportPolicy.duplicate(existing, incident)) return true
        check(prefs.edit().putBoolean(SafeModeKeys.disabled(scope.id), true)
                .putString(SafeModeKeys.incident(scope.id), incident.encode()).commit()) { "cannot persist safety report" }
        refresh(context)
        ModuleLog.warn("SAFE MODE [${scope.title}]: ${incident.reason}")
        HyperSettings.syncToFramework(context)
        return true
    }

    @Synchronized fun acknowledge(context: Context, statuses: List<SafeModeStatus>) {
        val editor = HyperSettings.localPrefs(context).edit()
        statuses.forEach { status -> status.incident?.let { editor.putString(SafeModeKeys.acknowledged(status.scope.id), it.id) } }
        editor.apply()
        refresh(context)
    }
}
