/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import android.content.Context
import io.github.YGHFv.HyperExtend.config.HyperSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SafeModeStatus(val scope: HyperScope, val disabled: Boolean, val incident: SafeModeIncident?, val acknowledged: Boolean)

/** Module-side state. Safety flags are deliberately excluded from backup/reset of features. */
internal object SafeModeSettings {
    private val mutable = MutableStateFlow<List<SafeModeStatus>>(emptyList())
    val state = mutable.asStateFlow()

    @Synchronized fun refresh(context: Context) {
        val prefs = HyperSettings.localPrefs(context)
        mutable.value = SCOPES.map { scope ->
            val incident = SafeModeIncident.decode(prefs.getString(SafeModeKeys.incident(scope.id), "").orEmpty())
            SafeModeStatus(scope, prefs.getBoolean(SafeModeKeys.disabled(scope.id), false) || incident != null,
                incident, incident == null || prefs.getString(SafeModeKeys.acknowledged(scope.id), "") == incident.id)
        }
    }

    @Synchronized fun setDisabled(context: Context, scope: HyperScope, disabled: Boolean): Boolean = runCatching {
        require(scope in SCOPES)
        val prefs = HyperSettings.localPrefs(context)
        val editor = prefs.edit().putBoolean(SafeModeKeys.disabled(scope.id), disabled)
        if (!disabled) {
            val previous = maxOf(prefs.getLong(SafeModeKeys.reset(scope.id), 0), prefs.getLong(FRAMEWORK_FUSE_RESET_KEY, 0))
            editor.putLong(SafeModeKeys.reset(scope.id), SafeModeKeys.nextReset(previous, System.currentTimeMillis()))
                .remove(SafeModeKeys.incident(scope.id)).remove(SafeModeKeys.acknowledged(scope.id))
        }
        check(editor.commit())
        refresh(context)
        HyperSettings.syncToFramework(context)
        true
    }.getOrElse { ModuleLog.error("safe mode setting failed", it); false }

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
