/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationManagementPolicy {
    fun ownerUid(uid: Int) = uid in 1..99999
    fun userChannelWrite(enabled: Boolean, fromUser: Boolean, callingUid: Int,
        systemCaller: Boolean, targetUid: Int) = enabled && fromUser && callingUid == 1000 && systemCaller && ownerUid(targetUid)
    fun directChannelUpdate(frames: List<Pair<String, String>>): Boolean {
        val t = NotificationManagementTargets
        val first = frames.firstOrNull { (owner, name) ->
            (owner.startsWith("android.app.NotificationChannel") || owner.startsWith("com.android.server.notification.")) &&
                !(owner == t.CHANNEL && name == "isBlockable")
        }
        return first == (t.update.owner to t.update.name)
    }
}
