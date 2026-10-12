package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object GmsSettingsTargets {
    const val PACKAGE = "com.miui.securitycenter"
    const val ACTIVITY = "com.miui.googlebase.ui.GmsCoreSettings"
    const val LEGACY_STARTER = "com.android.systemui.statusbar.phone.LegacyActivityStarterInternalImpl"
    val start = FiveGSettingsTargets.start
    val falsing = FiveGSettingsTargets.falsing
    val select = SystemUiMethod("com.android.systemui.statusbar.phone.ActivityStarterImpl",
        "getActivityStarterInternal", "com.android.systemui.statusbar.phone.ActivityStarterInternal")
    val methods = listOf(start, falsing, select)
    val starter = Triple(GmsTileTargets.FACTORY, "activityStarter", "dagger.Lazy")
}
