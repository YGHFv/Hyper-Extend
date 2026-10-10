/*
 * Copyright (C) 2023-2026 HyperCeiler Contributions
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.hostDisplayIdOrNull

import io.github.YGHFv.HyperExtend.core.compatibleVersionCode

import android.content.Context
import android.os.Looper
import io.github.YGHFv.HyperExtend.hook.HookRuntime
import io.github.YGHFv.HyperExtend.hook.HookSettings
import io.github.YGHFv.HyperExtend.hook.SafeModeRuntime

internal object AutoCollapseHooks {
    const val FEATURE = "control_center_auto_collapse"
    const val TILE = "com.android.systemui.qs.tileimpl.QSTileImpl"
    const val STATE = "com.android.systemui.plugins.qs.QSTile\$State"
    const val STATUS = "com.android.systemui.plugins.statusbar.StatusBarStateController"
    val status = SystemUiMethod(STATUS, "getState", "int")
    // These are concrete main-package callers; the abstract state query is resolved only.
    val callers = listOf(
        SystemUiMethod("com.android.systemui.plugins.qs.QSTile", "click", "void"),
        SystemUiMethod("com.android.systemui.qs.MiuiQSFragment", "clickTile", "void", listOf("android.content.ComponentName")),
        SystemUiMethod("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt\$\$ExternalSyntheticLambda8", "invoke", "java.lang.Object"),
        SystemUiMethod("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt\$\$ExternalSyntheticLambda9", "invoke", "java.lang.Object"),
        SystemUiMethod("com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter", "clickTile", "void", listOf("android.content.ComponentName")),
        SystemUiMethod("com.android.systemui.qs.tileimpl.MiuiQSTileBaseView\$\$ExternalSyntheticLambda0", "onClick", "void", listOf("android.view.View")),
        SystemUiMethod("com.android.systemui.qs.tiles.MiuiCellularTile", "\$r8\$lambda\$6TkkrUugCzSHNexsoGhUWms89ro", "void",
            listOf("com.android.systemui.qs.tiles.MiuiCellularTile"), isStatic = true),
        SystemUiMethod("com.android.systemui.qs.tiles.MiuiCellularTile", "click", "void", listOf("com.android.systemui.animation.Expandable")),
    )
    val methods = listOf(SystemUiTargets.tileClick, SystemUiTargets.collapsePanels, status) + callers
    val fields = listOf(
        Triple(TILE, "mContext", "android.content.Context"), Triple(TILE, "mHost", "com.android.systemui.qs.QSHost"),
        Triple(TILE, "mState", STATE), Triple(TILE, "mTileSpec", "java.lang.String"),
        Triple(TILE, "mShowingDetail", "boolean"), Triple(TILE, "mStatusBarStateController", STATUS),
        Triple(STATE, "state", "int"), Triple(STATE, "disabledByPolicy", "boolean"), Triple(STATE, "isTransient", "boolean"),
    )

    fun eligible(state: Int, disabled: Boolean, transient: Boolean, spec: String?, detail: Boolean, status: Int): Boolean =
        state in 1..2 && !disabled && !transient && !spec.isNullOrBlank() && spec != "edit" && !detail && status == 0

    fun install(loader: ClassLoader, settings: HookSettings): Int {
        val resolved = methods.associateWith { NotificationHooks.resolve(loader, it) ?: return 0 }
        val fields = AutoCollapseHooks.fields.associate { (owner, name, expected) ->
            name to Class.forName(owner, false, loader).getDeclaredField(name).apply {
                require(type.name == expected && !java.lang.reflect.Modifier.isStatic(modifiers)); isAccessible = true
            }
        }
        if (!resolved.filterKeys { it != status }.values.map { HookRuntime.deoptimize(it, "$FEATURE/caller") }.all { it }) return 0
        val collapse = resolved.getValue(SystemUiTargets.collapsePanels)
        val active = ThreadLocal<Any?>()
        var version: Long? = null
        fun host(tile: Any): Any? = runCatching {
            if (!settings.isOn(FEATURE) || Looper.myLooper() != Looper.getMainLooper()) return@runCatching null
            val context = fields.getValue("mContext").get(tile) as Context
            val currentVersion = version ?: context.packageManager.getPackageInfo("com.android.systemui", 0)
                .compatibleVersionCode.also { version = it }
            if (currentVersion != 202602260L || context.hostDisplayIdOrNull() != 0) return@runCatching null
            val state = fields.getValue("mState").get(tile) ?: return@runCatching null
            if (!eligible(fields.getValue("state").getInt(state), fields.getValue("disabledByPolicy").getBoolean(state),
                    fields.getValue("isTransient").getBoolean(state), fields.getValue("mTileSpec").get(tile) as? String,
                    fields.getValue("mShowingDetail").getBoolean(tile),
                    resolved.getValue(status).invoke(fields.getValue("mStatusBarStateController").get(tile)) as Int)) return@runCatching null
            fields.getValue("mHost").get(tile)?.takeIf { collapse.declaringClass.isInstance(it) }
        }.getOrNull()
        return if (HookRuntime.hook(resolved.getValue(SystemUiTargets.tileClick), "$FEATURE/click") { chain ->
                val tile = chain.thisObject ?: return@hook chain.proceed()
                if (active.get() === tile) return@hook chain.proceed()
                val candidate = host(tile)
                val previous = active.get()
                active.set(tile)
                try {
                    val result = chain.proceed()
                    // click() enqueues work. This is a collapse request after dispatch, NOT a
                    // success callback; leave all native execution and rejection paths intact.
                    if (candidate != null && !SafeModeRuntime.blocked && host(tile) === candidate)
                        collapse.invoke(candidate)
                    result
                } finally { if (previous == null) active.remove() else active.set(previous) }
            }) 1 else 0
    }
}
