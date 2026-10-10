/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object MediaDarkTargets {
    const val HEADER = "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView"
    private const val PREFIX = "com.android.systemui.statusbar.notification.style.vieweffect."
    private const val COMBINE = "com.android.systemui.statusbar.notification.style.view.MediaViewBinder\$bind\$1\$1\$invokeSuspend\$\$inlined\$combine\$1\$3"
    val effects = listOf("MediaViewNormalEffect", "MediaViewBlurEffect", "MediaViewGlassEffect",
        "MediaViewBlurOnKeyguardEffect", "MediaViewGlassOnKeyguardEffect", "MediaViewGlassOnKeyguardLightWallPaperEffect", "MediaViewGlassFullAodEffect")
        .map { SystemUiMethod(PREFIX + it, "apply", "void", listOf("java.lang.Object", "android.content.Context")) }
    val typedBlur = SystemUiMethod(PREFIX + "MediaViewBlurEffect", "apply", "void", listOf(HEADER, "android.content.Context"))
    val foreground = SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER, "updateForegroundColors", "void")
    val bind = SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER, "bindMediaData", "void", listOf("com.android.systemui.media.controls.shared.model.MediaData"))
    val detach = SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER, "detach", "void")
    val replaceHolder = SystemUiMethod(HEADER, "setMediaViewHolder", "void", listOf(SystemUiTargets.MEDIA_HOLDER))
    val artworkCallers = listOf(SystemUiTargets.mediaReinflate,
        SystemUiMethod(SystemUiTargets.MEDIA_NOTIFICATION, "access\$setTopMediaData", "void",
            listOf(SystemUiTargets.MEDIA_NOTIFICATION, "com.android.systemui.media.controls.shared.model.MediaData"), isStatic = true))
    val callers = listOf(
        SystemUiTargets.mediaAttach,
        SystemUiMethod(SystemUiTargets.MEDIA_NOTIFICATION + "\$configurationCallback\$1", "onUiModeChanged", "void"),
        SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER + "\$1\$1", "emit", "java.lang.Object", listOf("java.lang.Object", "kotlin.coroutines.Continuation")),
        SystemUiMethod(SystemUiTargets.MEDIA_CONTROLLER + "\$mediaFullAodListener\$1", "onFullAodChange", "void", listOf("boolean", "boolean")),
        SystemUiMethod(COMBINE, "invokeSuspend", "java.lang.Object", listOf("java.lang.Object")),
    )
}
