/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object HeaderWeatherTargets {
    const val HEADER = "com.android.systemui.qs.MiuiNotificationHeaderView"
    const val FETCHER = "com.miui.clock.utils.WeatherFetcherController"
    const val CALLBACK = "$FETCHER\$Callback"
    const val BEAN = "com.miui.clock.module.WeatherBean"
    const val USER_TRACKER = "com.android.systemui.settings.UserTrackerImpl"
    val inflate = SystemUiMethod(HEADER, "onFinishInflate", "void")
    val attach = SystemUiMethod(HEADER, "onAttachedToWindow", "void")
    val detach = SystemUiMethod(HEADER, "onDetachedFromWindow", "void")
    val fetch = SystemUiMethod(FETCHER, "fetchWeatherData", "void", listOf("android.content.Context", "boolean", CALLBACK))
    val reset = SystemUiMethod(FETCHER, "reset", "void")
    val callback = SystemUiMethod(CALLBACK, "updateWeatherInfo", "void", listOf(BEAN))
    val valid = SystemUiMethod(BEAN, "getTemperatureValid", "boolean")
    val temperature = SystemUiMethod(BEAN, "getTemperature", "int")
    val description = SystemUiMethod(BEAN, "getDescription", "java.lang.String")
    val city = SystemUiMethod(BEAN, "getCityName", "java.lang.String")
    val time = SystemUiMethod(BEAN, "getUpdateTime", "long")
    val user = SystemUiMethod(USER_TRACKER, "getUserId", "int")
    val userContext = SystemUiMethod(USER_TRACKER, "createCurrentUserContext", "android.content.Context", listOf("android.content.Context"))
    val capture = NavigationGeometryTargets.params
    val captureCallers = NavigationGeometryTargets.callers.filter { it.owner == NavigationGeometryTargets.BAR }
    val methods = listOf(inflate, attach, detach, fetch, reset, callback, valid, temperature, description, city, time,
        user, userContext, capture) + captureCallers
    val fields = listOf(
        Triple(HEADER, "mDateView", "com.android.systemui.statusbar.views.MiuiClock"),
        Triple(HEADER, "mLandClock", "com.android.systemui.statusbar.views.MiuiClock"),
        Triple(BEAN, "temperatureUnit", "int"),
        Triple(NavigationGeometryTargets.BAR, "mUserContextProvider", "com.android.systemui.settings.UserContextProvider"),
    )
}
