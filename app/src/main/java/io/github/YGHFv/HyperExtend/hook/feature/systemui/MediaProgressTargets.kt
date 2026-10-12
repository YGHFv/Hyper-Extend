/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaProgressTargets {
    const val BAR = "miuix.miuixbasewidget.widget.HyperProgressSeekBar"
    const val OWNER = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaSeekBarProgressOwner"
    const val PROGRESS = "com.android.systemui.media.controls.ui.viewmodel.SeekBarViewModel\$Progress"
    const val OBSERVER = "$OWNER\$progressObserver\$1"
    const val MATERIAL = "com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor"
    const val AOD = "com.android.systemui.statusbar.notification.fullaod.NotifiFullAodController"
    val flowValue = SystemUiMethod("kotlinx.coroutines.flow.StateFlow", "getValue", "java.lang.Object")
    val lazyGet = SystemUiMethod("dagger.Lazy", "get", "java.lang.Object")
    val draw = SystemUiMethod(BAR, "onDraw", "void", listOf("android.graphics.Canvas"))
    val changed = SystemUiMethod(OBSERVER, "onChanged", "void", listOf("java.lang.Object"))
    val observerCallers = listOf(
        SystemUiMethod("androidx.lifecycle.LiveData", "considerNotify", "void",
            listOf("androidx.lifecycle.LiveData\$ObserverWrapper")),
        SystemUiMethod("androidx.lifecycle.MediatorLiveData\$Source", "onChanged", "void", listOf("java.lang.Object")),
        SystemUiMethod("com.android.systemui.statusbar.notification.collection.NotifLiveDataImpl\$\$ExternalSyntheticLambda0",
            "invoke", "java.lang.Object"),
        SystemUiMethod("com.android.systemui.statusbar.notification.collection.NotifLiveDataImpl\$setValueAndProvideDispatcher\$1\$2",
            "run", "void"),
        SystemUiMethod(OWNER, "register", "void", listOf("$OWNER\$RenderStateConsumer")),
    )
    val methods = listOf(draw, changed, SystemUiTargets.mediaAttach, MediaDarkTargets.detach,
        SystemUiTargets.flipTinyScreen, SystemUiTargets.mediaReinflate, SystemUiTargets.mediaBoundsChanged,
        SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER, "onRenderState", "void",
            listOf("com.android.systemui.statusbar.notification.mediacontrol.MiuiSeekBarRenderState")),
        flowValue, lazyGet) + observerCallers
    val fields = listOf(
        Triple(SystemUiTargets.MEDIA_CONTROLLER, "holder", SystemUiTargets.MEDIA_HOLDER),
        Triple(SystemUiTargets.MEDIA_CONTROLLER, "seekBarProgressOwner", OWNER),
        Triple(SystemUiTargets.MEDIA_CONTROLLER, "notificationMaterialStateInteractor", MATERIAL),
        Triple(SystemUiTargets.MEDIA_CONTROLLER, "fullAodController", "dagger.Lazy"),
        Triple(MATERIAL, "isOnKeyguard", "kotlinx.coroutines.flow.ReadonlyStateFlow"),
        Triple(MATERIAL, "isShowingKeyguardNotification", "kotlinx.coroutines.flow.StateFlow"),
        Triple(MATERIAL, "enterFullAod", "kotlinx.coroutines.flow.ReadonlyStateFlow"),
        Triple(AOD, "mWillDoAodAnim", "boolean"),
        Triple(SystemUiTargets.MEDIA_HOLDER, "seekBar", BAR),
        Triple(SystemUiTargets.MEDIA_HOLDER, "elapsedTimeView", "android.widget.TextView"),
        Triple(OBSERVER, "this\$0", OWNER),
        Triple(BAR, "mDeviceLevel", "int"), Triple(BAR, "mProgressHeight", "int"),
        Triple(BAR, "mProgressSeekBarMaxHeight", "int"),
        Triple(BAR, "mForegroundPrimaryColor", "int"), Triple(BAR, "mBackgroundPrimaryColor", "int"),
        Triple(BAR, "mForegroundPrimaryDisableColor", "int"), Triple(BAR, "mBackgroundPrimaryDisableColor", "int"),
        Triple(BAR, "mIsDragging", "boolean"),
    ) + listOf("enabled", "listening", "playing", "scrubbing").map { Triple(PROGRESS, it, "boolean") }
}
