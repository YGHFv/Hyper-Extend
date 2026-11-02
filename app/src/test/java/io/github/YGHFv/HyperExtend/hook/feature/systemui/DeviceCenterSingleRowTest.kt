/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.DeviceCenterSingleRowSettings
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class DeviceCenterSingleRowTest {
    private class Device(val id: Int)
    private val detail = Any()
    private val empty = Any()
    private val otherController = Any()
    private fun select(items: List<Any>) =
        DeviceCenterSingleRowPolicy.select(items, { it is Device }, { it === detail })

    @Test fun emptyAndThirdPartyControllerRemainNative() {
        for (items in listOf(emptyList(), listOf(empty), listOf(otherController))) {
            assertSame(items, select(items))
        }
    }

    @Test fun oneToThreeDevicesKeepAllDevicesAndNativeEntry() {
        for (count in 1..3) {
            val items = List(count) { Device(it) } + detail
            assertSame(items, select(items))
            assertEquals(count + 1, select(items).size)
        }
    }

    @Test fun fourDevicesUseAllFourSlots() {
        val devices = List(4) { Device(it) }
        val items = devices + detail
        val result = select(items)
        assertEquals(4, result.size)
        devices.forEachIndexed { index, device -> assertSame(device, result[index]) }
        assertFalse(result.any { it === detail })
        assertEquals(5, items.size)
    }

    @Test fun overflowReusesSameNativeDetailObjectAndFirstThreeDevices() {
        for (count in listOf(5, 6, 8, 20, 100)) {
            val devices = List(count) { Device(it) }
            val items = devices + detail
            val result = select(items)
            assertEquals(4, result.size)
            assertSame(detail, result.last())
            (0..2).forEach { assertSame(devices[it], result[it]) }
            assertEquals(count + 1, items.size)
            assertEquals(count, devices.size)
        }
    }

    @Test fun unknownOrMalformedListsFailClosed() {
        for (items in listOf(
            List(6) { Device(it) },
            List(5) { Device(it) } + otherController,
            List(4) { Device(it) } + empty + detail,
            List(4) { Device(it) } + detail + detail,
        )) assertSame(items, select(items))
    }

    @Test fun repeatedSelectionAndNativeRebuildKeepDiffListsIndependent() {
        val devices = List(6) { Device(it) }
        val pending = devices.toList()
        val native = ArrayList<Any>(devices + detail)
        val old = ArrayList(native)
        val selected = select(native)
        native.clear()
        native.addAll(selected)
        assertSame(native, select(native))
        assertEquals(7, old.size)
        assertEquals(6, pending.size)
        assertEquals(4, native.size)
        // 关闭后宿主仍从完整 pending 列表重建，不会永久丢失被折叠的设备。
        val rebuilt = ArrayList<Any>(pending + detail)
        assertEquals(7, rebuilt.size)
        assertSame(devices[5], rebuilt[5])
    }

    @Test fun onlyEnabledMainThreadOutsideSafeModeCanChangeDisplayList() {
        for (enabled in listOf(false, true))
            for (safeMode in listOf(false, true))
                for (main in listOf(false, true))
                    assertEquals(enabled && !safeMode && main,
                        DeviceCenterSingleRowPolicy.eligible(enabled, safeMode, main))
    }

    @Test fun featureIsOptInInNewControlCenterWithoutDetailPage() {
        val feature = featureById(DeviceCenterSingleRowSettings.FEATURE)!!
        assertEquals("单行融合设备", feature.title)
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(FeaturePanel.NEW_CONTROL_CENTER, feature.panel)
        assertTrue(feature.requirement!!.contains("OS3"))
        assertTrue(feature.requirement.contains("OS4"))
    }

    private fun descriptor(type: String) = when (type) {
        "void" -> "V"
        "boolean" -> "Z"
        "int" -> "I"
        else -> "L${type.replace('.', '/')};"
    }

    @Test fun bothHostsHaveExactConstructorFieldsAndDeoptimizationCallers() {
        for (host in listOf("os3", "os4")) {
            val fixture = javaClass.getResourceAsStream("/device-center-single-row-$host-host-members.txt")!!
                .bufferedReader().use { it.readLines().toSet() }
            val t = DeviceCenterSingleRowTargets
            for (spec in t.callers + t.mode) {
                val signature = (if (spec.isStatic) "static " else "instance ") + descriptor(spec.owner) +
                    "->${spec.name}(" + spec.parameters.joinToString("") { descriptor(it) } +
                    ")" + descriptor(spec.returns)
                assertTrue("$host $signature", signature in fixture)
            }
            for ((owner, name, type) in t.fields)
                assertTrue("$host $owner $name",
                    "instance ${descriptor(owner)}->$name:${descriptor(type)}" in fixture)
            assertTrue("instance ${descriptor(t.DIFF)}-><init>(Ljava/util/ArrayList;Ljava/util/ArrayList;)V" in fixture)
            for (name in listOf("MODE_COLLAPSED", "MODE_1_ROW", "MODE_2_ROWS"))
                assertTrue("static ${descriptor(t.MODE)}->$name:${descriptor(t.MODE)}" in fixture)
        }
    }

    @Test fun bothHostsAssignSameNewListBeforeDiffAndDeriveNativeHeightAfterDispatch() {
        for (host in listOf("os3", "os4")) {
            val code = javaClass.getResourceAsStream("/device-center-single-row-$host-flow.txt")!!
                .bufferedReader().use { it.readText() }
            val assign = code.indexOf("iput-object v2, p0,")
            val construct = code.indexOf("invoke-direct {v0, v1, v2},")
            val diff = code.indexOf("->calculateDiff(")
            val dispatch = code.indexOf("->dispatchUpdatesTo(")
            val mode = code.indexOf("->getMode()")
            val height = code.indexOf("->getDeviceCenterEntryHeight(")
            assertTrue(host, assign >= 0 && assign < construct && construct < diff && diff < dispatch &&
                dispatch < mode && mode < height)
            assertTrue(code.contains("->deviceItems:Ljava/util/ArrayList;"))
            assertTrue(code.contains("const/4 v0, 0x4"))
            assertTrue(code.contains("->MODE_1_ROW:"))
            assertTrue(code.contains("->MODE_2_ROWS:"))
            assertTrue(code.contains("->HEIGHT:Lmiuix/animation/property/ViewProperty;"))
        }
    }
}
