/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SystemUiMethod(
    val owner: String,
    val name: String,
    val returns: String,
    val parameters: List<String> = emptyList(),
    val isStatic: Boolean = false,
) {
    fun matches(method: Method): Boolean = method.name == name &&
        method.returnType.name == returns && method.parameterTypes.map { it.name } == parameters &&
        Modifier.isStatic(method.modifiers) == isStatic
}

/** Exact signatures read from MT workspace wot5ikcq (SystemUI 17.03.260226.r, SDK 37). */
internal object SystemUiTargets {
    const val EXPANDED_NOTIFICATION = "com.android.systemui.statusbar.notification.ExpandedNotification"
    const val ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry"
    const val KEYGUARD_STATUS_VM = "com.android.systemui.statusbar.ui.viewmodel.KeyguardStatusBarViewModel"
    const val PIN_VIEW = "com.android.keyguard.KeyguardPINView"
    const val SHADE_CONTAINER = "com.android.systemui.shade.NotificationsQuickSettingsContainer"
    const val NOTIFICATION_NUM_VM = "com.miui.systemui.notification.view.viewmodel.NotificationNumStateViewModel"
    const val CONTROL_SETTINGS = "com.miui.systemui.controlcenter.data.repository.ControlCenterSettingsRepositoryImpl"
    const val MEDIA_NOTIFICATION = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaNotificationControllerImpl"
    const val MEDIA_CONTROLLER = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewControllerImpl"
    const val MEDIA_HOLDER = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaViewHolder"

    val mediaLoadLayout = SystemUiMethod(MEDIA_NOTIFICATION, "loadLayout", "void")
    val mediaUpdateLayout = SystemUiMethod(MEDIA_NOTIFICATION, "updateLayout\$1", "void")
    val mediaAttach = SystemUiMethod(MEDIA_CONTROLLER, "attach", "void", listOf(MEDIA_HOLDER))
    val mediaSeamless = SystemUiMethod(MEDIA_CONTROLLER, "setSeamless", "void",
        listOf("com.android.systemui.media.controls.shared.model.MediaData"))
    val flipTinyScreen = SystemUiMethod("com.miui.utils.configs.MiuiConfigs", "isFlipTinyScreen", "boolean",
        listOf("android.content.Context"), isStatic = true)
    val mediaReinflate = SystemUiMethod("com.android.systemui.statusbar.notification.stack.NotificationSectionsManager", "reinflateViews", "void")
    val mediaBoundsChanged = SystemUiMethod(MEDIA_NOTIFICATION + "\$configurationCallback\$1", "onMaxBoundsChanged", "void", listOf("boolean"))

    val pinInflate = SystemUiMethod(PIN_VIEW, "onFinishInflate", "void")
    val facePossible = SystemUiMethod("com.android.keyguard.KeyguardUpdateMonitor", "isUnlockWithFacePossible", "boolean")
    val fingerprintPossible = SystemUiMethod(
        "com.android.keyguard.stub.MiuiKeyguardUpdateMonitorStub", "isUnlockWithFingerprintPossible", "boolean", listOf("int"),
    )
    val clipboardChanged = SystemUiMethod(
        "com.android.systemui.clipboardoverlay.ClipboardListener", "onPrimaryClipChanged", "void",
    )
    val indicationUpdate = SystemUiMethod(
        "com.android.systemui.keyguard.KeyguardIndicationRotateTextViewController", "updateIndication", "void",
        listOf("int", "com.android.systemui.keyguard.KeyguardIndication", "boolean"),
    )
    val indicationType = SystemUiMethod(
        indicationUpdate.owner, "indicationTypeToString", "java.lang.String", listOf("int"), isStatic = true,
    )
    val tileClick = SystemUiMethod(
        "com.android.systemui.qs.tileimpl.QSTileImpl", "click", "void", listOf("com.android.systemui.animation.Expandable"),
    )
    val collapsePanels = SystemUiMethod(
        "com.android.systemui.qs.pipeline.domain.adapter.MiuiQSHostAdapter", "collapsePanels", "void",
    )
    val notificationSettings = SystemUiMethod(
        "com.miui.systemui.notification.NotificationSettingsHelper", "startAppNotificationSettings", "void",
        listOf("android.content.Context", "java.lang.String", "java.lang.String", "int", "java.lang.String"), isStatic = true,
    )
    val controlSettingsEmit = SystemUiMethod(
        CONTROL_SETTINGS + "\$special\$\$inlined\$map\$1\$2", "emit", "java.lang.Object",
        listOf("java.lang.Object", "kotlin.coroutines.Continuation"),
    )
    val themeOverlays = SystemUiMethod("com.android.systemui.theme.ThemeOverlayController", "createOverlays", "void", listOf("int"))
    val focusState = SystemUiMethod(
        "com.miui.systemui.notification.NotificationSettingsManager", "canShowFocusState", "int",
        listOf("android.content.Context", "java.lang.String"),
    )
    val focusAppState = focusState.copy(name = "canShowFocusStateApp")
    val mediaAction = SystemUiMethod(
        "com.android.systemui.media.controls.domain.pipeline.MediaActionsKt\$\$ExternalSyntheticLambda0",
        "invoke", "java.lang.Object", listOf("java.lang.Object"),
    )
    val mediaActionRun = SystemUiMethod(
        "com.android.systemui.statusbar.notification.mediacontrol.MediaActionsInjector\$getCustomAction\$2", "run", "void",
    )
    val ignoreFold = SystemUiMethod(
        "com.android.systemui.statusbar.notification.utils.NotificationUtil", "shouldIgnoreEntry", "boolean", listOf(ENTRY), isStatic = true,
    )
    const val FOLD_COORDINATOR = "com.android.systemui.statusbar.notification.collection.coordinator.FoldCoordinator"
    val foldCallers = listOf(
        SystemUiMethod(FOLD_COORDINATOR + "\$collectionListener\$1", "onEntryAdded", "void", listOf(ENTRY)),
        SystemUiMethod(FOLD_COORDINATOR + "\$collectionListener\$1", "onEntryUpdated", "void", listOf(ENTRY)),
        SystemUiMethod(FOLD_COORDINATOR + "\$shadeExpansionListener\$1", "onPanelExpansionChanged\$1", "void", listOf("com.android.systemui.shade.ShadeExpansionChangeEvent")),
        SystemUiMethod(FOLD_COORDINATOR + "\$alarmScheduler\$2\$1\$onReceive\$1", "run", "void"),
    )

    val showOnKeyguard = SystemUiMethod(EXPANDED_NOTIFICATION, "canShowOnKeyguard", "boolean")
    val forceHideOnKeyguard = SystemUiMethod(
        "com.android.systemui.statusbar.notification.policy.NotificationFilterController",
        "forceHideOnKeyguard", "boolean", listOf(ENTRY), isStatic = true,
    )
    val alert = SystemUiMethod(
        "com.android.systemui.statusbar.notification.policy.MiuiAlertManager",
        "buzzBeepBlink", "void", listOf(ENTRY),
    )
    val transparent = SystemUiMethod(
        "com.android.systemui.statusbar.notification.row.NotificationRowContentBinderInjectorImpl",
        "isTransparent", "boolean", listOf("android.content.Context"),
    )
    val miniWindowBar = SystemUiMethod(
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRowInjector",
        "updateMiniWindowBar", "void",
    )
    val canSlide = SystemUiMethod(
        "com.android.systemui.statusbar.notification.policy.AppMiniWindowManagerImpl",
        "canNotificationSlide", "boolean", listOf("java.lang.String", "android.app.PendingIntent"),
    )

    val all = listOf(
        showOnKeyguard, forceHideOnKeyguard, alert, transparent, miniWindowBar, canSlide,
        pinInflate, clipboardChanged, indicationUpdate, indicationType, tileClick, collapsePanels,
        notificationSettings, controlSettingsEmit, themeOverlays, focusState, focusAppState, ignoreFold,
        facePossible, fingerprintPossible, mediaAction, mediaActionRun,
        mediaLoadLayout, mediaUpdateLayout, mediaAttach, mediaSeamless, flipTinyScreen,
        mediaReinflate, mediaBoundsChanged,
    ) + foldCallers
}
