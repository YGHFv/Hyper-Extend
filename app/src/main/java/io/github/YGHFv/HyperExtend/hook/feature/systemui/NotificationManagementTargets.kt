/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationManagementTargets {
    const val FINGERPRINT = "Xiaomi/pandora/pandora:17/CP2A.260605.016/OS4.0.0.38.XBLCNXM:user/release-keys"
    const val BACKEND = "com.android.settings.notification.MiuiNotificationBackend"
    const val ROW = "$BACKEND\$AppRow"
    const val FILTER = "miui.util.NotificationFilterHelper"
    const val CHANNEL = "android.app.NotificationChannel"
    const val PREFERENCES = "com.android.server.notification.PreferencesHelper"
    val row = SystemUiMethod(BACKEND, "loadAppRow", ROW,
        listOf("android.content.Context", "android.content.pm.PackageManager", "android.content.pm.PackageInfo"))
    val toggle = SystemUiMethod("com.android.settings.notification.NotificationSettingsHelper", "setNotificationsEnabledForPackage",
        "void", listOf("android.content.Context", "java.lang.String", "boolean"), isStatic = true)
    val forced = SystemUiMethod(FILTER, "isNotificationForcedFor", "boolean", listOf("android.content.Context", "java.lang.String"), isStatic = true)
    val enable = SystemUiMethod(FILTER, "enableNotifications", "void", listOf("android.content.Context", "java.lang.String", "int", "boolean"), isStatic = true)
    val enableForContext = SystemUiMethod(FILTER, "enableNotifications", "void", listOf("android.content.Context", "java.lang.String", "boolean"), isStatic = true)
    val fields = listOf(Triple(ROW, "systemApp", "boolean"), Triple(ROW, "lockedChannelId", "java.lang.String"), Triple(ROW, "uid", "int"))
    val settingsCallers = listOf(
        SystemUiMethod("com.android.settings.notification.BaseNotificationSettings", "loadAppRowData", "void"),
    ) + listOf("com.android.settings.notification.AppNotificationSettings", "com.android.settings.notification.app.AppNotificationSettings").flatMap {
        listOf(SystemUiMethod("$it\$1", "doInBackground", "java.lang.Void", listOf("[Ljava.lang.Void;")),
            SystemUiMethod("$it\$2", "onPreferenceChange", "boolean", listOf("androidx.preference.Preference", "java.lang.Object")))
    }
    val settingsMethods = listOf(row, toggle, forced, enable, enableForContext) + settingsCallers
    val update = SystemUiMethod(PREFERENCES, "updateNotificationChannel", "void",
        listOf("java.lang.String", "int", CHANNEL, "boolean", "int", "boolean"))
    val blockable = SystemUiMethod(CHANNEL, "isBlockable", "boolean")
    val updateCaller = SystemUiMethod("com.android.server.notification.NotificationManagerService", "updateNotificationChannelInt", "void",
        listOf("java.lang.String", "int", CHANNEL, "boolean"))
    val serverMethods = listOf(update, blockable, updateCaller)
}
