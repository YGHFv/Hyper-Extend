/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

// Exact members and full dispatch callers: docs/tile-material-evidence.json.
internal object TileMaterialTargets {
    val blend = SystemUiMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColors", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken"), true)
    val blendTransition = SystemUiMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColors", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken", "miuix.theme.token.ColorBlendToken", "float", "boolean"), true)
    val write = SystemUiMethod("miuix.core.util.MiuiBlurUtils", "setBackgroundBlendConfig", "boolean", listOf("android.view.View", "java.util.ArrayList"), true)
    val clear = SystemUiMethod("miuix.core.util.MiuiBlurUtils", "clearBackgroundBlendConfig", "boolean", listOf("android.view.View"), true)
    val add = SystemUiMethod("miuix.core.util.MiuiBlurUtils", "addBackgroundBlenderColor", "boolean", listOf("android.view.View", "int", "int"), true)
    val materialType = SystemUiMethod("miuix.core.util.MiuiBlurUtils", "setMiViewMaterialType", "void", listOf("android.view.View", "int"), true)
    val classic = SystemUiMethod("miui.systemui.util.MiBackgroundStyle", "isClassicMode", "boolean", listOf(), true)
    val material = SystemUiMethod("miui.systemui.controlcenter.utils.ControlCenterUtils", "getBackgroundMaterialOpenedWithoutSuperPower", "boolean", listOf("android.content.Context"), true)
    val theme = SystemUiMethod("miui.systemui.util.ThemeUtils", "getDefaultPluginTheme", "boolean", listOf(), false)
    val restricted = SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSItemView\$Companion", "isRestrictedCompat", "boolean", listOf("com.android.systemui.plugins.qs.QSTile\$State"), false)
    val warning = SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSItemView\$Companion", "isTrafficWarning", "boolean", listOf("com.android.systemui.plugins.qs.QSTile\$State"), false)
    val offToken = SystemUiMethod("miui.systemui.util.MiuiColorBlendToken", "getCC_TILE_DEFAULT_BLEND_COLORS", "miuix.theme.token.ColorBlendToken", listOf(), false)
    val onToken = SystemUiMethod("miui.systemui.util.MiuiColorBlendToken", "getCC_TILE_ON_BLEND_COLORS", "miuix.theme.token.ColorBlendToken", listOf(), false)
    val writers = listOf(write, clear, add, materialType)
    val renders = listOf(blend, blendTransition)
    val callers = listOf(
        SystemUiMethod("com.android.systemui.QSControlMiPlayDetailContent", "setContentBgBlurForActivity", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.QSControlMiPlayDetailHeader", "initUI", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.QSControlMiPlayDetailHeader", "setPreNextBlur", "void", listOf("boolean"), false),
        SystemUiMethod("com.android.systemui.QSControlMiPlayDetailHeader", "setVideoPreNextBlur", "void", listOf("boolean"), false),
        SystemUiMethod("com.android.systemui.QSControlMiPlayDetailHeader", "updateSeekBarBlur", "void", listOf(), false),
        SystemUiMethod("com.android.systemui.miui.volume.Util", "setMiViewBlurAndBlendColor", "void", listOf("android.view.View", "int", "miuix.theme.token.ColorBlendToken"), true),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.devicecenter.devices.EmptyDeviceViewHolder", "updateIconBlendColor", "void", listOf(), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder", "setPreNextBlur", "void", listOf("boolean"), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder", "updateDeviceIconBlur", "void", listOf(), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase", "setContentBgColor", "void", listOf("float"), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelTilesDelegate", "applyMaterialStyle", "void", listOf("miui.systemui.controlcenter.qs.tileview.QSTileItemView", "com.android.systemui.plugins.qs.QSTile"), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.media.MediaPanelDelegate", "updateBlur", "void", listOf(), false),
        SystemUiMethod("miui.systemui.controlcenter.panel.secondary.volume.VolumePanelAnimator", "animUpdateProgressColor", "void", listOf("android.content.Context", "float", "android.view.View", "android.view.View", "boolean"), false),
        SystemUiMethod("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", "applyTileBackgroundStyle", "void", listOf("float", "float"), false),
        SystemUiMethod("miui.systemui.controlcenter.widget.AnimateColorView", "updateIconColor", "void", listOf("miuix.theme.token.ColorBlendToken", "int"), false),
        SystemUiMethod("miui.systemui.plugin.brightness.slider.BrightnessWindowSliderView", "updateBlendBlur", "void", listOf(), false),
        SystemUiMethod("miui.systemui.ui.material.ColorBlendStyle", "apply", "void", listOf("android.view.View"), false),
        SystemUiMethod("miui.systemui.ui.material.MaterialBackgroundExt", "transitionBlendMode", "void", listOf("android.view.View", "miui.systemui.ui.material.MaterialToken", "miui.systemui.ui.material.MaterialToken", "float", "boolean"), false),
        SystemUiMethod("miui.systemui.util.CommonUtils", "clearMiBlurBlendEffect", "void", listOf("android.view.View"), false),
        SystemUiMethod("miui.systemui.util.MiBackgroundStyle", "setMiBackgroundStyle", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken", "miui.systemui.util.BionicsToken"), true),
        SystemUiMethod("miui.systemui.util.MiBackgroundStyle", "setMiBackgroundStyle", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken", "miuix.theme.token.ColorBlendToken", "float", "miui.systemui.util.BionicsToken", "miui.systemui.util.BionicsToken", "float", "boolean", "boolean", "float", "miui.systemui.util.LightParams"), true),
        SystemUiMethod("miui.systemui.util.MiBlurCompat", "clearMiBackgroundBlendColorCompat", "boolean", listOf("android.view.View"), true),
        SystemUiMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColorConfig", "boolean", listOf("android.view.View", "java.util.ArrayList"), true),
        SystemUiMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColors\$default", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken", "miuix.theme.token.ColorBlendToken", "float", "boolean", "int", "java.lang.Object"), true),
        SystemUiMethod("miui.systemui.util.MiBlurCompat", "setMiBackgroundBlendColors", "void", listOf("android.view.View", "miuix.theme.token.ColorBlendToken", "miuix.theme.token.ColorBlendToken", "float"), true),
        SystemUiMethod("miuix.androidbasewidget.widget.TextProgressBar", "clearMaterialEffects", "void", listOf(), false),
        SystemUiMethod("miuix.appcompat.app.SearchViewMaterialImpl", "clearMaterialEffects", "void", listOf("android.view.View"), false),
        SystemUiMethod("miuix.appcompat.widget.HyperPopupWindow", "clearMaterial", "void", listOf("android.view.View"), false),
        SystemUiMethod("miuix.core.util.HyperMaterialUtils", "applyContainerWithGlass", "void", listOf("android.view.View", "miuix.core.util.MaterialConfig\$GlassConfig"), true),
        SystemUiMethod("miuix.core.util.HyperMaterialUtils", "clearElement", "void", listOf("android.view.View"), true),
        SystemUiMethod("miuix.core.util.MiuiBlurUtils", "applyColorBlendConfig", "void", listOf("android.view.View", "miuix.core.util.MaterialConfig\$BlurConfig"), true),
        SystemUiMethod("miuix.core.util.MiuiBlurUtils", "clearBlurConfig", "void", listOf("android.view.View"), true),
        SystemUiMethod("miuix.core.util.MiuiBlurUtils", "enableContentBlur", "void", listOf("android.view.View", "boolean", "miuix.core.util.MaterialConfig\$ColorBlendConfig"), true),
        SystemUiMethod("miuix.core.util.MiuiBlurUtils", "enableContentBlur", "void", listOf("android.view.View", "boolean", "[I", "[I"), true),
        SystemUiMethod("miuix.core.util.MiuiBlurUtils", "setBackgroundBlendConfig", "boolean", listOf("android.view.View", "[I", "[I"), true),
        SystemUiMethod("miuix.miuixbasewidget.widget.FilterSortView2", "clearTabMaterial", "void", listOf("miuix.miuixbasewidget.widget.FilterSortView2\$TabView"), false),
        SystemUiMethod("miuix.miuixbasewidget.widget.FilterSortView", "clearMaterialEffects", "void", listOf(), false),
        SystemUiMethod("miuix.miuixbasewidget.widget.PageIndicator", "clearMaterialEffects", "void", listOf("int"), false),
        SystemUiMethod("miuix.popupwidget.widget.PopupView", "clearMaterialEffects", "void", listOf(), false),
        SystemUiMethod("miuix.view.MiuiBlurUiHelper", "createBlurParamsIfNeeded", "void", listOf(), false),
        SystemUiMethod("miuix.view.MiuiBlurUiHelper", "enableTextBlur", "void", listOf("android.widget.TextView", "boolean", "[I", "[I"), true),
        SystemUiMethod("miuix.view.MiuiBlurUiHelper", "handleCancelBlur", "void", listOf(), false),
        SystemUiMethod("miuix.view.MiuiBlurUiHelper", "refreshBlur", "void", listOf(), false),
    )
    val methods = (renders + writers + listOf(classic, material, theme, restricted, warning, offToken, onToken) + callers).distinct()
    val fields = listOf(
        Triple("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", "state", "com.android.systemui.plugins.qs.QSTile\$State"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", "icon", "miui.systemui.widget.ImageView"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", "isDetailTile", "boolean"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSTileItemIconView", "card", "boolean"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSCardItemView", "state", "com.android.systemui.plugins.qs.QSTile\$State"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSTileItemView", "mode", "miui.systemui.controlcenter.panel.main.MainPanelController\$Mode"),
        Triple("com.android.systemui.plugins.qs.QSTile\$State", "state", "int"),
        Triple("com.android.systemui.plugins.qs.QSTile\$State", "activeBgColor", "int"),
        Triple("com.android.systemui.plugins.qs.QSTile\$State", "disabledByPolicy", "boolean"),
        Triple("com.android.systemui.plugins.qs.QSTile\$State", "isTransient", "boolean"),
        Triple("miuix.theme.token.ColorBlendToken", "colors", "[I"),
        Triple("miuix.theme.token.ColorBlendToken", "blendModes", "[I"),
        Triple("miuix.theme.token.ColorBlendToken", "extraBlendParams", "[F"),
        Triple("miuix.theme.token.ColorBlendToken", "fallbackColors", "[I"),
        Triple("miuix.theme.token.ColorBlendToken", "fallbackBlendModes", "[I"),
        Triple("miuix.theme.token.ColorBlendToken", "fallbackExtraBlendParams", "[F"),
        Triple("miui.systemui.util.ThemeUtils", "INSTANCE", "miui.systemui.util.ThemeUtils"),
        Triple("miui.systemui.util.MiuiColorBlendToken", "INSTANCE", "miui.systemui.util.MiuiColorBlendToken"),
        Triple("miui.systemui.controlcenter.qs.tileview.QSItemView", "Companion", "miui.systemui.controlcenter.qs.tileview.QSItemView\$Companion"),
    )
}
