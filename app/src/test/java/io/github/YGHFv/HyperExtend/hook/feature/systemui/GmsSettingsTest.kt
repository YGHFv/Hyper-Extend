package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class GmsSettingsTest {
    @Test fun onlyExactEnabledExportedSystemSettingsWithPermissionAreOffered() {
        fun target(packageName: String? = GmsSettingsTargets.PACKAGE,
            className: String? = GmsSettingsTargets.ACTIVITY, flags: List<Boolean> = List(5) { true }) =
            GmsSettingsPolicy.target(packageName, className, flags[0], flags[1], flags[2], flags[3], flags[4])
        assertTrue(target())
        assertFalse(target(packageName = "com.android.settings"))
        assertFalse(target(className = "com.miui.googlebase.ui.OtherActivity"))
        assertFalse(target(packageName = null))
        assertFalse(target(className = null))
        for (disabled in 0..4) assertFalse(target(flags = List(5) { it != disabled }))
    }

    @Test fun inertRefactorAndUnknownStarterImplementationsAreNotAdvertised() {
        assertTrue(GmsSettingsPolicy.implemented(GmsSettingsTargets.LEGACY_STARTER))
        assertFalse(GmsSettingsPolicy.implemented("com.android.systemui.statusbar.phone.ActivityStarterInternalImpl"))
        assertFalse(GmsSettingsPolicy.implemented("com.android.systemui.statusbar.phone.ActivityStarterImpl"))
        assertFalse(GmsSettingsPolicy.implemented("custom.LegacyActivityStarterInternalImpl"))
        assertFalse(GmsSettingsPolicy.implemented(null))
    }

    @Test fun settingsActionsNeverEnterThePackageTransactionBranch() {
        for (action in GmsTilePolicy.Action.entries) for (valid in listOf(false, true)) {
            for (usable in listOf(false, true)) for (unchanged in listOf(false, true)) {
                assertEquals(action == GmsTilePolicy.Action.TOGGLE && valid && usable && unchanged,
                    GmsTilePolicy.shouldWrite(action, valid, usable, unchanged))
                if (action == GmsTilePolicy.Action.SETTINGS)
                    assertFalse(GmsTilePolicy.shouldWrite(action, valid, usable, unchanged))
            }
        }
    }

    @Test fun exactOptionalNativeContractsAreBackedByCompleteCachedMembers() {
        fun descriptor(type: String): String = when (type) {
            "void" -> "V"; "boolean" -> "Z"; "int" -> "I"
            else -> "L${type.replace('.', '/')};"
        }
        val signatures = javaClass.getResourceAsStream("/gms-settings-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (method in GmsSettingsTargets.methods) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
            assertFalse(method.isStatic)
        }
        val (owner, name, type) = GmsSettingsTargets.starter
        assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signatures)
        assertEquals("android.os.UserHandle", GmsSettingsTargets.start.parameters.last())
        assertEquals(8, signatures.size)
    }
}
