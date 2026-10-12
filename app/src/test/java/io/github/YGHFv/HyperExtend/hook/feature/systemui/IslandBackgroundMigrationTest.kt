/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.IslandMediaSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class IslandBackgroundMigrationTest {
    @Test fun backgroundAndAmbientChoicesHaveDistinctRealSemantics() {
        assertFalse(IslandBackgroundOptions().custom)
        assertFalse(IslandBackgroundOptions(ambient = 1).needsArtwork)
        assertTrue(IslandBackgroundOptions(ambient = 1).custom)
        assertTrue(IslandBackgroundOptions(ambient = 2).needsArtwork)
        for (mode in 1..4) {
            assertTrue(IslandBackgroundOptions(mode = mode).custom)
            assertTrue(IslandBackgroundOptions(mode = mode, ambient = 1).needsArtwork)
        }
        assertFalse(IslandBackgroundOptions(mode = 9, ambient = 2).custom)
    }
    @Test fun safetyAndUncertainDisplaysKeepNative() {
        fun eligible(enabled: Boolean = true, version: Long = 202602260, display: Int? = 0,
            attached: Boolean = true, shown: Boolean = true, awake: Boolean = true, unlocked: Boolean = true) =
            IslandBackgroundPolicy.eligible(enabled, version, display, attached, shown, awake, unlocked)
        assertTrue(eligible())
        assertFalse(eligible(enabled = false)); assertFalse(eligible(version = 1))
        assertFalse(eligible(display = null)); assertFalse(eligible(display = 1))
        assertFalse(eligible(attached = false)); assertFalse(eligible(shown = false))
        assertFalse(eligible(awake = false)); assertFalse(eligible(unlocked = false))
    }
    @Test fun sharedAmbientClockFreezesWhilePausedAndDoesNotJumpAfterSleep() {
        val clock = IslandAmbientClock()
        assertEquals(0f, clock.frame(100, true), 0f)
        val moving = clock.frame(133, true)
        assertTrue(moving > 0f)
        assertEquals(moving, clock.frame(166, false), 0f)
        assertEquals(moving, clock.frame(60000, true), 0f)
        assertEquals(moving, clock.frame(60000, true), 0f)
        assertTrue(clock.frame(90000, true) < moving + .02f)
        clock.reset()
        assertEquals(0f, clock.frame(100000, false), 0f)
    }
    @Test fun nativeAlphaTakeoverIsNotOverwrittenDuringCleanup() {
        val state = IslandOwnedValue<Float>()
        assertEquals(0f, state.apply(.7f, 0f), 0f)
        assertEquals(.7f, state.restore(0f), 0f)
        state.apply(.7f, 0f)
        assertEquals(.4f, state.restore(.4f), 0f)
        state.apply(.7f, 0f)
        state.apply(.4f, 0f)
        assertEquals(.4f, state.restore(0f), 0f)
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"; "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun completeCurrentMembersCoverBothHoldersShaderIdentityAndThemeCaller() {
        val members = javaClass.getResourceAsStream("/island-background-current-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        for (m in IslandBackgroundTargets.methods.distinct()) {
            val signature = descriptor(m.owner) + "->" + m.name + "(" +
                m.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(m.returns)
            assertTrue(signature, signature in members)
        }
        for ((owner, name, type) in IslandBackgroundTargets.fields) {
            val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
            assertTrue(signature, signature in members)
        }
        assertTrue(IslandBackgroundTargets.uiMode in IslandBackgroundTargets.callers)
        assertTrue(IslandBackgroundTargets.fields.any { it.second == "dummyHolder" })
    }
    @Test fun independentFeatureDefaultsToNativeAndDoesNotEnableOtherMediaParents() {
        val feature = featureById(IslandMediaSettings.BACKGROUND)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.ISLAND_MEDIA, feature.panel)
        assertTrue(IslandMediaSettings.backgroundOptions.all { !it.defaultEnabled })
        assertFalse(featureById("media_card_background")!!.defaultEnabled)
    }
}
