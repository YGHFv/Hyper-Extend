package io.github.YGHFv.HyperExtend.hook.feature.systemui

internal object GmsSettingsPolicy {
    fun target(packageName: String?, className: String?, systemApp: Boolean,
        applicationEnabled: Boolean, activityEnabled: Boolean, exported: Boolean, permissionGranted: Boolean) =
        packageName == GmsSettingsTargets.PACKAGE && className == GmsSettingsTargets.ACTIVITY &&
            systemApp && applicationEnabled && activityEnabled && exported && permissionGranted

    fun implemented(className: String?) = className == GmsSettingsTargets.LEGACY_STARTER
}
