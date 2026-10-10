/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object NotificationImportanceSettingsTargets {
    const val BASE = "com.android.settings.notification.BaseNotificationSettings"
    const val PAGE = "com.android.settings.notification.ChannelNotificationSettings"
    const val APP_PAGE = "com.android.settings.notification.app.ChannelNotificationSettings"
    val pages = listOf(PAGE, APP_PAGE)
    const val BACKEND = "com.android.settings.notification.MiuiNotificationBackend"
    const val PREF = "androidx.preference.Preference"
    const val DROPDOWN = "miuix.preference.DropDownPreference"
    const val CHANNEL = "android.app.NotificationChannel"
    val resume = SystemUiMethod(PAGE, "onResume", "void")
    val visible = SystemUiMethod(BASE, "setPrefVisible", "void", listOf(PREF, "boolean"))
    val remove = SystemUiMethod(PAGE, "removeDefaultPrefs", "void")
    val dependents = SystemUiMethod(PAGE, "updateDependents", "void", listOf("boolean"))
    val configurable = SystemUiMethod(BASE, "isChannelConfigurable", "boolean", listOf(CHANNEL))
    val blockable = SystemUiMethod(BASE, "isChannelBlockable", "boolean", listOf(CHANNEL))
    val included = SystemUiMethod(BASE, "isIncludedInFilter", "boolean", listOf("java.lang.String"))
    val update = SystemUiMethod(BACKEND, "updateChannel", "void", listOf("java.lang.String", "int", CHANNEL))
    val read = SystemUiMethod(BACKEND, "getChannel", CHANNEL, listOf("java.lang.String", "int", "java.lang.String", "java.lang.String"))
    // findSpinnerIndexOfValue is private on Settings 17; use its public forwarding API.
    val index = SystemUiMethod(DROPDOWN, "findIndexOfValue", "int", listOf("java.lang.String"))
    val setIndex = SystemUiMethod(DROPDOWN, "setValueIndex", "void", listOf("int"))
    val setPersistent = SystemUiMethod(PREF, "setPersistent", "void", listOf("boolean"))
    val resumes = pages.map { resume.copy(owner = it) }
    val dependentUpdates = pages.map { dependents.copy(owner = it) }
    val hooks = resumes + visible
    val queries = dependentUpdates + listOf(configurable, blockable, included, update, read, index, setIndex, setPersistent)
    val callers = resumes + pages.map { remove.copy(owner = it) }
}
