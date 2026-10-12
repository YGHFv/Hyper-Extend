/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.IslandMediaSettings
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StrongToastPolicy
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StrongToastScope
import io.github.YGHFv.HyperExtend.hook.feature.statusbar.StrongToastTargets
import org.junit.Assert.*
import org.junit.Test

class IslandMediaMigrationTest {
    @Test fun commandScopeIsIdentityBoundAndNestedFailureRestoresTheOuterDelivery() {
        val scope = StrongToastScope()
        val owner = Any(); val bundle = Any(); val inner = Any()
        assertFalse(scope.matches(owner, bundle))
        scope.within(owner, bundle) {
            assertTrue(scope.matches(owner, bundle))
            assertFalse(scope.matches(owner, Any()))
            runCatching { scope.within(owner, inner) {
                assertFalse(scope.matches(owner, bundle))
                assertTrue(scope.matches(owner, inner))
                error("parser/delivery")
            } }
            assertTrue(scope.matches(owner, bundle))
        }
        assertFalse(scope.matches(owner, bundle))
    }
    @Test fun strongToastNeverUsesAnUnrelatedCommandOrUnknownDisplay() {
        assertTrue(StrongToastPolicy.eligible(true, 202602260, 0, "strong_toast_action"))
        assertFalse(StrongToastPolicy.eligible(true, 202602260, null, "strong_toast_action"))
        assertFalse(StrongToastPolicy.eligible(true, 202602260, 1, "strong_toast_action"))
        assertFalse(StrongToastPolicy.eligible(false, 202602260, 0, "strong_toast_action"))
        assertFalse(StrongToastPolicy.eligible(true, 1, 0, "strong_toast_action"))
        assertFalse(StrongToastPolicy.eligible(true, 202602260, 0, "charge"))
    }
    @Test fun ownedValuesRestoreOnlyOurChangesAndAcceptFreshNativeBaselines() {
        val state = IslandOwnedValue<Int>()
        assertEquals(8, state.apply(0, 8))
        assertEquals(8, state.apply(8, 8))
        assertEquals(0, state.restore(8))
        assertEquals(8, state.apply(4, 8))
        assertEquals(0, state.restore(0))
        state.apply(0, 8)
        state.apply(4, 8)
        assertEquals(4, state.restore(8))
        assertEquals(8, state.restore(8))
    }
    @Test fun islandDefaultsKeepNativeConstraintsButExplicitFourDpArtistMarginIsApplied() {
        assertTrue(IslandMediaPolicy.edits(MediaLayoutOptions(), 20, 2f).isEmpty())
        assertEquals(listOf(MediaConstraintEdit("setMargin", listOf(0x7f0b0522, 3, 8))),
            IslandMediaPolicy.edits(MediaLayoutOptions(), 40, 2f))
        val edits = IslandMediaPolicy.edits(MediaLayoutOptions(order = 2), 20, 2f)
        assertTrue(MediaConstraintEdit("setHorizontalChainStyle", listOf(0x7f0b0059, 1)) in edits)
        assertFalse(edits.any { it.method == "setVisibility" })
        assertEquals(listOf(2, 1, 3, 0, 4), MediaLayoutPolicy.order(2))
    }
    @Test fun textAndIconPolicyHasFiniteBoundsAndNoGuessedMainRoleForNotificationActions() {
        assertEquals(36f, IslandMediaPolicy.textSize(180, 2f)!!)
        assertNull(IslandMediaPolicy.textSize(180, Float.NaN))
        assertNull(IslandMediaPolicy.textSize(180, -1f))
        assertNull(IslandMediaPolicy.textSize(Int.MAX_VALUE, 2f))
        for (i in 0..4) {
            assertEquals(i in 1..3, IslandMediaPolicy.mainAction(true, i))
            assertFalse(IslandMediaPolicy.mainAction(false, i))
        }
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun method(t: SystemUiMethod) = descriptor(t.owner) + "->" + t.name +
        "(" + t.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(t.returns)
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    @Test fun fullCurrentSignaturesIncludeBothHoldersNativeTemplateAndAllCallers() {
        val members = fixture("island-media-current")
        for (t in IslandMediaTargets.methods) assertTrue(method(t), method(t) in members)
        for ((owner, name, type) in IslandMediaTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in members)
        assertEquals(41, members.size)
        assertTrue("Landroidx/constraintlayout/widget/ConstraintSet;-><init>()V" in members)
        val strong = fixture("strong-toast-current")
        for (t in listOf(StrongToastTargets.entry, StrongToastTargets.delivery, StrongToastTargets.caller))
            assertTrue(method(t) in strong)
        assertTrue(StrongToastTargets.context.let { (owner, name, type) ->
            descriptor(owner) + "->" + name + ":" + descriptor(type)
        } in strong)
        assertEquals(4, strong.size)
    }
    @Test fun realFeaturesAreDefaultOffAndHaveIndependentPanelsAndTextUnits() {
        for (key in listOf(IslandMediaSettings.LAYOUT, IslandMediaSettings.TEXT, IslandMediaSettings.PROGRESS, "status_bar_hide_strong_toast"))
            assertFalse(featureById(key)!!.defaultEnabled)
        assertEquals(FeaturePanel.ISLAND_MEDIA, featureById(IslandMediaSettings.LAYOUT)!!.panel)
        assertTrue(IslandMediaSettings.text.all { it.unit == " sp" })
        assertTrue(IslandMediaSettings.options.all { !it.defaultEnabled })
    }
    @Test fun islandProgressUsesRealPairAndNativeObserverWithoutReplacingTheSeekbar() {
        val members = fixture("island-progress-current")
        for (t in IslandProgressTargets.methods) assertTrue(method(t), method(t) in members)
        for ((owner, name, type) in IslandProgressTargets.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in members)
        assertEquals(31, members.size)
        assertTrue(IslandProgressTargets.fields.any { it.second == "dummyHolder" })
        assertEquals(MediaProgressTargets.BAR,
            IslandProgressTargets.fields.single { it.second == "seekBar" }.third)
        assertFalse(MediaProgressGeometry.animate(true, true, true, true))
        assertTrue(MediaProgressGeometry.animate(true, true, true, false))
    }
}
