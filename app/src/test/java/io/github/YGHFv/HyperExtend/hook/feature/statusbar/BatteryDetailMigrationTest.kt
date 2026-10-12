/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.BatteryDetailSettings
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.panel
import io.github.YGHFv.HyperExtend.hook.feature.systemui.ChargingRequestState
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class BatteryDetailMigrationTest {
    private val sample = BatteryDetailSample(1_250_000, 4_000, 315, 2, 1, 10_000)
    private fun text(format: BatteryDetailFormat, value: BatteryDetailSample = sample) =
        BatteryDetailPolicy.text(value, 10_000, 2_000, format, Locale.US)

    @Test fun allContentModesPreserveUpstreamOrderAndUnitsWithBatterySidePower() {
        assertEquals("31.5\u2103\n1.25A", text(BatteryDetailFormat()))
        assertEquals("5.00W", text(BatteryDetailFormat(content = 2)))
        assertEquals("1.25A", text(BatteryDetailFormat(content = 3)))
        assertEquals("31.5\u2103\n5.00W", text(BatteryDetailFormat(content = 4)))
        assertEquals("1.25A\n5.00W", text(BatteryDetailFormat(content = 5)))
        assertEquals("5.00W 31.5\u2103", text(BatteryDetailFormat(content = 4, reverse = true, single = true)))
        assertEquals("31.5\n1.25", text(BatteryDetailFormat(hideUnit = 1)))
        assertEquals("31.5\n1.25A", text(BatteryDetailFormat(hideUnit = 2)))
        assertEquals("31.5\u2103\n1.25", text(BatteryDetailFormat(hideUnit = 3)))
    }

    @Test fun frameworkSignRatioBoundsMissingAndExactZeroAreNotInventedTelemetry() {
        val discharging = sample.copy(microamps = -900_000, temperature = 300, status = 3, plugged = 0)
        assertEquals("30\u2103\n-900mA", text(BatteryDetailFormat(), discharging))
        assertEquals("30.0\u2103\n900mA", text(BatteryDetailFormat(decimal = true, positive = true), discharging))
        assertEquals("1.25A", text(BatteryDetailFormat(content = 3, ratio = true), sample.copy(microamps = 1_250)))
        assertNull(text(BatteryDetailFormat(content = 3, ratio = true)))
        assertEquals("0mA", text(BatteryDetailFormat(content = 3), sample.copy(microamps = 0)))
        assertEquals("31.5\u2103", text(BatteryDetailFormat(), sample.copy(microamps = Int.MIN_VALUE)))
        assertNull(text(BatteryDetailFormat(content = 2), sample.copy(millivolts = null)))
        assertNull(text(BatteryDetailFormat(content = 2), sample.copy(millivolts = Int.MAX_VALUE)))
        assertNull(text(BatteryDetailFormat(), sample.copy(microamps = null, temperature = null)))
        assertNull(text(BatteryDetailFormat(chargingOnly = true), discharging))
        assertNull(text(BatteryDetailFormat(chargingOnly = true), sample.copy(status = 5)))
    }

    @Test fun samplesExpireAndCancelledAsyncCompletionsCannotReviveDetachedLabels() {
        assertNull(BatteryDetailPolicy.text(sample, 14_001, 2_000, BatteryDetailFormat(), Locale.US))
        assertNull(BatteryDetailPolicy.text(sample, 9_999, 2_000, BatteryDetailFormat(), Locale.US))
        val state = ChargingRequestState()
        val old = state.begin(0)!!
        assertNull(state.begin(1))
        state.cancel()
        val fresh = state.begin(2)!!
        assertFalse(state.complete(old, 3, 1_000))
        assertTrue(state.complete(fresh, 4, 1_000))
        assertNull(state.begin(1_003))
        assertNotNull(state.begin(1_004))
    }

    @Test fun widthHonorsNativeFixedAreaAndMalformedDensityNeverCreatesAView() {
        assertEquals(100, BatteryDetailPolicy.width(400, null, 0, 2f))
        assertEquals(40, BatteryDetailPolicy.width(400, 160, 120, 2f))
        assertEquals(0, BatteryDetailPolicy.width(400, 100, 120, 2f))
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(0, BatteryDetailPolicy.width(400, null, 0, density))
    }

    @Test fun orderSwapsNativeGeometryNotChildIndicesAndPreservesGapsInBothDirections() {
        assertEquals(37 to 4, BatteryPositionPolicy.swap(64, 4, 24, 28, 57, false))
        assertEquals(4 to 28, BatteryPositionPolicy.swap(64, 37, 57, 4, 33, true))
        assertNull(BatteryPositionPolicy.swap(64, 20, 40, 30, 60, false))
        assertNull(BatteryPositionPolicy.swap(64, 4, 24, 28, 70, false))
        assertNull(BatteryPositionPolicy.swap(64, 4, 4, 28, 50, false))
        assertNull(BatteryPositionPolicy.swap(64, 37, 57, 4, 33, false))
        assertNull(BatteryPositionPolicy.swap(64, -1, 20, 28, 50, false))
    }

    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun method(target: SystemUiMethod) = descriptor(target.owner) + "->" + target.name +
        "(" + target.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(target.returns)
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun currentHostHooksAndTypedFieldsHaveCompleteSignatures() {
        val detail = BatteryDetailTargets
        val detailSet = fixture("battery-detail-current")
        assertTrue(method(detail.inflate) in detailSet)
        detail.fields.forEach { (owner, name, type) ->
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in detailSet)
        }
        val order = BatteryOrderTargets
        val orderSet = fixture("battery-order-current")
        assertTrue(method(order.layout) in orderSet)
        order.fields.forEach { (name, type) ->
            assertTrue(descriptor(order.OWNER) + "->" + name + ":" + descriptor(type) in orderSet)
        }
        assertEquals(3, detailSet.size)
        assertEquals(15, orderSet.size)
        assertFalse(detail.inflate.isStatic)
        assertFalse(order.layout.isStatic)
    }

    @Test fun settingsAreDefaultOffAndDoNotOfferAnUnimplementedRightSlot() {
        val feature = featureById(BatteryDetailSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.BATTERY, feature.panel)
        assertEquals(7, feature.options.size)
        assertEquals(9, feature.config.size)
        assertFalse(feature.options.any { it.id.endsWith(".at_right") })
    }
}
