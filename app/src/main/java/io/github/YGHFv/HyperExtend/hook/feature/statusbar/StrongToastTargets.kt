/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod

internal object StrongToastTargets {
    const val OWNER = "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl"
    val entry = SystemUiMethod(OWNER, "setStatus", "void",
        listOf("int", "java.lang.String", "android.os.Bundle"))
    val delivery = SystemUiMethod(OWNER, "handleDeviceNotification", "void",
        listOf("android.os.Bundle", "com.android.systemui.devicenotification.bean.DeviceNotificationModel"))
    val caller = entry.copy(owner = "com.miui.systemui.statusbar.CommandQueueDelegate")
    val context = Triple(OWNER, "context", "android.content.Context")
}
