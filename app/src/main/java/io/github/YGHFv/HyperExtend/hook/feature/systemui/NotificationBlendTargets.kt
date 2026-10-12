/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

// Exact current-host signatures; see docs/notification-blend-evidence.json.
internal object NotificationBlendTargets {
    val blend = SystemUiMethod("com.android.systemui.statusbar.notification.utils.NotificationUtil", "applyElementViewBlend", "void", listOf("android.content.Context", "android.view.View", "boolean", "[I", "boolean"), isStatic = true)
    val overload = SystemUiMethod("com.android.systemui.statusbar.notification.utils.NotificationUtil", "applyElementViewBlend", "void", listOf("android.content.Context", "android.view.View", "boolean", "int", "int", "int", "int", "boolean"), isStatic = true)
    val scopes = linkedMapOf(
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurEffect", "apply", "void", listOf("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationBlurOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassFullAodEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.EXCLUDED,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.FocusNotificationGlassOnKeyguardLightWallPaperEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationBlurEffect", "apply", "void", listOf("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", "android.content.Context")) to NotificationBlendScene.HEADS_UP,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationBlurEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.HEADS_UP,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationGlassDarkEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.HEADS_UP,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.HeadsUpNotificationGlassEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.HEADS_UP,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewBlurEffect", "apply", "void", listOf("com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewBlurEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewBlurOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassFullAodEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.EXCLUDED,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.MediaViewGlassOnKeyguardLightWallPaperEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.ModalDialogBlurEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.ModalDialogGlassEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowBlurEffect", "apply", "void", listOf("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowBlurEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowBlurOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.SHADE,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassFullAodEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.EXCLUDED,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassOnKeyguardEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
        SystemUiMethod("com.android.systemui.statusbar.notification.style.vieweffect.NotificationRowGlassOnKeyguardLightWallPaperEffect", "apply", "void", listOf("java.lang.Object", "android.content.Context")) to NotificationBlendScene.KEYGUARD,
    )
    val callers = listOf(
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.ExpandableNotificationRowViewBinder", "bindInternal\$updateEffect", "void", listOf("com.android.systemui.statusbar.notification.style.domain.NotificationMaterialStateInteractor", "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", "kotlin.jvm.internal.Ref\$ObjectRef", "com.miui.systemui.material.NotificationViewType", "kotlin.jvm.internal.Ref\$ObjectRef"), isStatic = true),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.GroupCollapseButtonViewBinder\$bind\$1\$1\$invokeSuspend\$\$inlined\$combine\$1\$3", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.GroupHeaderTextViewBinder\$bind\$1\$1\$invokeSuspend\$\$inlined\$combine\$1\$3", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.MediaViewBinder\$bind\$1\$1\$invokeSuspend\$\$inlined\$combine\$1\$3", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.ModalDialogViewBinder\$bind\$1\$1\$1", "emit", "java.lang.Object", listOf("java.lang.Object", "kotlin.coroutines.Continuation")),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.NotificationDismissViewBinder\$bind\$1\$1\$3\$1", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
        SystemUiMethod("com.android.systemui.statusbar.notification.style.view.NotificationMenuViewBinder\$bind\$1\$1\$invokeSuspend\$\$inlined\$combine\$1\$3", "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
    )
    val methods = listOf(blend, overload) + scopes.keys + callers
}
