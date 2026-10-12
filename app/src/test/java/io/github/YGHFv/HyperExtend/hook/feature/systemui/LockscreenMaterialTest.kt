/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.*
import org.junit.Assert.*
import org.junit.Test

class LockscreenMaterialTest {
    @Test fun defaultOffAndRealRadiusAndColorInputsArePersisted() {
        val feature = featureById(LockscreenMaterialSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.LOCK_SHORTCUTS, feature.panel)
        assertFalse(feature.options.single().defaultEnabled)
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        assertEquals(32, LockscreenMaterialSettings.radius("bad"))
        assertEquals(0x3cffffff, LockscreenMaterialSettings.color(""))
        assertEquals(0, LockscreenMaterialSettings.color("#00000000"))
    }
    @Test fun realViewBoundsLimitCircleWithoutChangingTouchGeometry() {
        assertEquals(100, LockscreenMaterialPolicy.diameter(32, 3f, 100, 150))
        assertEquals(64, LockscreenMaterialPolicy.diameter(32, 1f, 100, 150))
        assertNull(LockscreenMaterialPolicy.diameter(32, 3f, 0, 100))
        assertNull(LockscreenMaterialPolicy.diameter(32, Float.NaN, 100, 100))
    }
    @Test fun translucentBackgroundKeepsNativeTintAndOpaqueColorUsesContrast() {
        assertNull(LockscreenMaterialPolicy.foreground(0x5cffffff))
        assertEquals(-1, LockscreenMaterialPolicy.foreground(0xff000000.toInt()))
        assertEquals(0xff000000.toInt(), LockscreenMaterialPolicy.foreground(-1))
    }
    private fun descriptor(type: String) = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
    private fun signature(method: SystemUiMethod) = descriptor(method.owner) + "->" + method.name +
        "(" + method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
    @Test fun allNativeBackdropMethodsHaveFullCachedFrameworkSignatures() {
        val fixture = javaClass.getResourceAsStream("/owned-backdrop-framework-members.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (method in OwnedBackdropTargets.methods) assertTrue(signature(method), signature(method) in fixture)
        assertEquals(13, OwnedBackdropTargets.methods.size)
        assertTrue("Landroid/view/CrossWindowBlurListeners;->CROSS_WINDOW_BLUR_SUPPORTED:Z" in fixture)
    }
    @Test fun exactPluginLifecycleAndHostCapabilityDeclarationsAreSeparate() {
        val fixture = javaClass.getResourceAsStream("/lockscreen-material-host-members.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (method in LockscreenMaterialTargets.hooks + LockscreenMaterialTargets.caller +
            LockscreenMaterialTargets.releaseCallers + LockscreenMaterialTargets.queries + OwnedBackdropTargets.allowed) {
            assertTrue(signature(method), signature(method) in fixture)
        }
        assertTrue(descriptor(LockscreenShortcutTargets.VIEW) + "-><init>(" +
            LockscreenMaterialTargets.constructorParameters.joinToString("") { descriptor(it) } + ")V" in fixture)
        assertTrue(descriptor(LockscreenMaterialTargets.MANAGER) + "->INSTANCE:" + descriptor(LockscreenMaterialTargets.MANAGER) in fixture)
        assertEquals(14, fixture.size)
    }
}
