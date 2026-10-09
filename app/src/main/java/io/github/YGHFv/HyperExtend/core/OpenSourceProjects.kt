/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

internal data class OpenSourceProject(val name: String, val url: String)

// Project-level acknowledgements; feature-level provenance stays in FeatureCatalog.
internal val OPEN_SOURCE_PROJECTS = listOf(
    OpenSourceProject("HyperCeiler", "https://github.com/ReChronoRain/HyperCeiler"),
    OpenSourceProject("Cemiuiler", "https://github.com/Cemiuiler-Development-Team/Cemiuiler"),
    OpenSourceProject("Miuix", "https://github.com/compose-miuix-ui/miuix"),
    OpenSourceProject("libxposed", "https://github.com/libxposed/api"),
    OpenSourceProject("MIUINativeNotifyIcon", "https://github.com/fankes/MIUINativeNotifyIcon"),
    OpenSourceProject("Android Notification Icon Project", "https://github.com/BetterAndroid/android-notification-icon-project"),
    OpenSourceProject("HyperWallpaperMonet", "https://github.com/PengDingkang/HyperWallpaperMonet"),
    OpenSourceProject("HyperVolumeANC", "https://github.com/zhhhyyyyyy/HyperVolumeANC"),
    OpenSourceProject("HyperPasskey", "https://github.com/Howard20181/HyperPasskey"),
    OpenSourceProject("DIY NFC", "https://github.com/Xposed-Modules-Repo/com.zhizi42.diymiuicard"),
    OpenSourceProject("AndroidX", "https://github.com/androidx/androidx"),
    OpenSourceProject("Android Open Source Project", "https://source.android.com"),
)
