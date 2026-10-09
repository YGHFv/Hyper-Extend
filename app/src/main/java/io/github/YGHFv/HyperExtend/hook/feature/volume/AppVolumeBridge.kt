/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import android.content.Intent
import android.os.Bundle

internal object AppVolumeBridge {
    const val QUERY = "io.github.YGHFv.HyperExtend.action.QUERY_APP_VOLUME"
    const val WRITE = "io.github.YGHFv.HyperExtend.action.WRITE_APP_VOLUME"
    const val END = "io.github.YGHFv.HyperExtend.action.END_APP_VOLUME"
    const val DATA_ONLY = "hyperextend.appVolumeDataOnly"
    const val TOKEN = "session"
    const val VERSION = 1
    private const val ERROR = "error"

    fun failure(reason: String) = Bundle().apply { putInt("version", VERSION); putString(ERROR, reason) }
    fun failureReason(bundle: Bundle?): String? = bundle?.takeIf { it.getInt("version") == VERSION }?.getString(ERROR)

    data class App(val uid: Int, val packageName: String, val label: String, val level: Int, val icon: ByteArray?)
    data class Snapshot(val token: String, val apps: List<App>)

    fun decode(bundle: Bundle?): Snapshot? = runCatching {
        if (bundle == null || bundle.getInt("version") != VERSION) return null
        val token = bundle.getString(TOKEN)?.takeIf { it.length in 16..64 } ?: return null
        val count = bundle.getInt("count")
        if (count !in 1..AppVolumeBridgePolicy.MAX_APPS) return null
        val apps = (0 until count).map { index ->
            val row = checkNotNull(bundle.getBundle("app$index"))
            val uid = row.getInt("uid", -1)
            val pkg = checkNotNull(row.getString("package"))
            val level = row.getInt("level", -1)
            check(uid >= 10000 && pkg.length in 1..255 && AppVolumeBridgePolicy.levelValid(level))
            val icon = row.getByteArray("icon")?.takeIf { it.size <= 8192 }
            App(uid, pkg, row.getString("label").orEmpty().take(100), level, icon)
        }
        check(apps.distinctBy { it.uid }.size == apps.size)
        Snapshot(token, apps)
    }.getOrNull()

    fun encode(snapshot: Snapshot) = Bundle().apply {
        putInt("version", VERSION); putString(TOKEN, snapshot.token); putInt("count", snapshot.apps.size)
        snapshot.apps.forEachIndexed { index, app -> putBundle("app$index", Bundle().apply {
            putInt("uid", app.uid); putString("package", app.packageName); putString("label", app.label)
            putInt("level", app.level); putByteArray("icon", app.icon)
        }) }
    }

    fun request(action: String, token: String? = null) = Intent(action)
        .setPackage("com.miui.misound").addFlags(Intent.FLAG_RECEIVER_FOREGROUND).putExtra(TOKEN, token)
}
