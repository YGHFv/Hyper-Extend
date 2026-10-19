/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

internal object TaplusTileHooks {
    private val adapter = NativeToggleTileHooks(object : NativeToggleBackend {
        override val feature = TaplusTilePolicy.FEATURE
        override val spec = TaplusTilePolicy.SPEC
        override val label = "Taplus"
        override val iconName = "ic_qs_taplus"
        override val settingsComponent = ComponentName(TaplusTilePolicy.PACKAGE, TaplusTilePolicy.ACTIVITY)
        private val uri = Uri.parse("content://${TaplusTilePolicy.AUTHORITY}")
        override fun supported(context: Context): Boolean {
            val pm = context.packageManager
            val provider = pm.resolveContentProvider(TaplusTilePolicy.AUTHORITY, 0) ?: return false
            return provider.enabled && provider.exported &&
                provider.packageName == TaplusTilePolicy.PACKAGE && provider.name == TaplusTilePolicy.PROVIDER &&
                provider.applicationInfo.enabled && provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
                provider.readPermission == TaplusTilePolicy.PERMISSION && provider.writePermission == TaplusTilePolicy.PERMISSION &&
                context.checkSelfPermission(TaplusTilePolicy.PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        override fun read(context: Context): Boolean? {
            val result = context.contentResolver.call(uri, "getControlStatus", null, null) ?: return null
            return if (result.containsKey(TaplusTilePolicy.STATUS)) result.get(TaplusTilePolicy.STATUS) as? Boolean else null
        }
        override fun write(context: Context, enabled: Boolean) {
            context.contentResolver.call(uri, "setControlStatus", TaplusTilePolicy.argument(enabled), null)
        }
    })
    val ready get() = adapter.ready
    fun install(loader: ClassLoader) = adapter.install(loader)
}
