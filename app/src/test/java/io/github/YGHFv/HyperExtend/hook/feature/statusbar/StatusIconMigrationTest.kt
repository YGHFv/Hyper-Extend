/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod
import org.junit.Assert.*
import org.junit.Test

class StatusIconMigrationTest {
    @Test fun slotModesCopyOnlyTheSelectedEntriesAndKeepNativeRestrictionsSeparate() {
        val native = listOf("native", "wifi", "wifi")
        for (center in listOf(false, true)) for (mode in listOf(0, 1, 2, 11, 12, 99)) {
            val result = StatusIconPolicy.blockList(native, mapOf("wifi" to mode, "new" to mode), center)
            assertTrue("native" in result)
            val blocked = StatusIconPolicy.blocked(mode, center)
            assertEquals(blocked ?: true, "wifi" in result)
            assertEquals(blocked ?: false, "new" in result)
            assertEquals(listOf("native", "wifi", "wifi"), native)
            if (blocked == null) assertEquals(native, result)
        }
        assertEquals(true, StatusIconPolicy.controlCenter("QS"))
        for (role in listOf("HOME", "KEYGUARD", "QS_FAKE"))
            assertEquals(false, StatusIconPolicy.controlCenter(role))
        for (role in listOf(null, "SHADE_CARRIER_GROUP", "UNKNOWN"))
            assertNull(StatusIconPolicy.controlCenter(role))
    }

    @Test fun hostAndOwnershipGatesNeverRestoreOverANativeTakeover() {
        for (code in listOf(null, 202602260L, 202602261L))
            for (display in listOf(null, 0, 1)) for (thread in listOf(false, true))
                assertEquals(code == 202602260L && display == 0 && thread,
                    StatusIconPolicy.eligible(code, display, thread))
        for (current in listOf(0, 4, 8)) for (native in listOf(0, 4, 8)) for (owned in listOf(false, true))
            assertEquals(if (current == 8 && owned) native else null,
                StatusIconPolicy.restoreVisibility(current, native, owned))
        assertFalse(StatusIconPolicy.hideOperatorIcon(null, true))
        assertFalse(StatusIconPolicy.hideOperatorIcon(202602260L, false))
        assertTrue(StatusIconPolicy.hideOperatorIcon(202602260L, true))
        assertEquals(listOf("native"),
            StatusIconPolicy.restoreList(listOf("native", "wifi"), listOf("native"), listOf("native", "wifi")))
        assertNull(StatusIconPolicy.restoreList(listOf("native", "system-added"),
            listOf("native"), listOf("native", "wifi")))
    }

    @Test fun notificationLimitPreservesSuppressionAndNativeLimitTypeAccounting() {
        for (type in listOf("MaximumAmount", "MaximumIndex")) {
            assertEquals(0, StatusIconPolicy.notificationLimit(0, 15, type, 15))
            assertEquals(0, StatusIconPolicy.notificationLimit(3, 0, type, 15))
            assertEquals(15, StatusIconPolicy.notificationLimit(3, 100, type, 100))
            assertEquals(1, StatusIconPolicy.notificationLimit(3, 100, type, -1))
            assertEquals(4, StatusIconPolicy.notificationLimit(3, 4, type, 15))
        }
        assertNull(StatusIconPolicy.notificationLimit(-1, 5, "MaximumAmount", 10))
        assertNull(StatusIconPolicy.notificationLimit(3, -1, "MaximumAmount", 10))
        assertNull(StatusIconPolicy.notificationLimit(3, 10, "new-host-type", 10))
        // MaximumIndex counts successful bindings within the chosen prefix, not the raw requested count.
        val requested = StatusIconPolicy.notificationLimit(3, 5, "MaximumIndex", 4)!!
        assertEquals(2, listOf(true, false, true, false, true).take(requested).count { it })
    }

    @Test fun mergedMobileCollectorsDoNotConfuseSpeechSatelliteOrNetworkTypeWithRoaming() {
        for (id in -1..6) {
            assertEquals(when (id) {
                0 -> MobileMarkerPolicy.Marker.VOWIFI
                2 -> MobileMarkerPolicy.Marker.ROAMING
                else -> null
            }, MobileMarkerPolicy.marker(true, id))
            assertEquals(when (id) {
                3 -> MobileMarkerPolicy.Marker.SMALL_ROAMING
                4 -> MobileMarkerPolicy.Marker.ACTIVITY
                else -> null
            }, MobileMarkerPolicy.marker(false, id))
        }
        for (update in listOf(false, true)) for (visible in listOf(false, true)) {
            assertEquals(Pair(update || visible, false),
                MobileMarkerPolicy.hiddenPair(MobileMarkerPolicy.Marker.ROAMING, update, visible))
            assertEquals(Pair(update, false),
                MobileMarkerPolicy.hiddenPair(MobileMarkerPolicy.Marker.ACTIVITY, update, visible))
        }
        assertNull(MobileMarkerPolicy.hiddenPair(MobileMarkerPolicy.Marker.VOWIFI, true, true))
    }

    private class Concrete { @JvmField var value: ArrayList<String> = arrayListOf("native") }
    @Test fun incompatibleConcreteFieldsCannotBeOverwrittenWithAnInterfaceLookalike() {
        val target = Concrete()
        val original = target.value
        assertFalse(StatusBarFields.write(target, "value", listOf("replacement")))
        assertSame(original, target.value)
        assertFalse(StatusBarFields.write(target, "missing", null))
        val replacement = arrayListOf("typed")
        assertTrue(StatusBarFields.write(target, "value", replacement))
        assertSame(replacement, target.value)
    }

    @Test fun wifiAlwaysShowOnlyRecoversTheExactNativeFourAndFiveFilter() {
        for (mode in listOf(0, 1, 2, 11)) for (mapper in -1..4) for (raw in -1..9)
            for (filtered in listOf(0, 4, 5, 6)) {
                assertEquals(if (mode == 1 && mapper == 2 && raw in 4..5 && filtered == 0) raw else null,
                    WifiStandardPolicy.recover(202602260L, mode, mapper, raw, filtered))
            }
        assertNull(WifiStandardPolicy.recover(null, 1, 2, 4, 0))
        assertNull(WifiStandardPolicy.recover(202602261L, 1, 2, 5, 0))
        val config = io.github.YGHFv.HyperExtend.core.STATUS_BAR_FEATURES
            .single { it.id == "status_bar_icons" }.config
            .filterIsInstance<io.github.YGHFv.HyperExtend.core.HyperChoice>()
            .single { it.key == io.github.YGHFv.HyperExtend.core.WIFI_STANDARD_KEY }
        assertEquals(listOf("0", "1", "2"), config.entries.map { it.variant })
    }

    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }
    private fun method(set: Set<String>, m: SystemUiMethod) {
        val signature = descriptor(m.owner) + "->" + m.name + "(" +
            m.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(m.returns)
        assertTrue(signature, signature in set)
    }
    private fun field(set: Set<String>, owner: String, name: String, type: String) {
        val signature = descriptor(owner) + "->" + name + ":" + descriptor(type)
        assertTrue(signature, signature in set)
    }

    @Test fun batteryVisibilityUsesExactCurrentFieldsRefreshAndDispatchSignatures() {
        val set = fixture("battery-visibility-current")
        (BatteryStyleTargets.refresh + BatteryStyleTargets.callers).forEach { method(set, it) }
        BatteryVisibilityTargets.fields.forEach { (name, type) -> field(set, BatteryStyleTargets.OWNER, name, type) }
        assertEquals(24, set.size)
    }

    @Test fun wifiHooksDoNotDependOnTheRemovedRepositoryConstructorOrReplaceFlowFields() {
        val t = WifiIndicatorTargets
        val set = fixture("wifi-indicator-current")
        (listOf(t.bind) + t.render + t.callers).forEach { method(set, it) }
        t.fields.forEach { (owner, name, type) -> field(set, owner, name, type) }
        assertTrue(t.bind.isStatic)
        assertTrue(t.render.all { !it.isStatic && it.parameters == listOf("java.lang.Object") })
        assertEquals(16, set.size)
    }

    @Test fun wifiStandardMappingHasExactInterfaceAndResumeSignatures() {
        val t = WifiStandardTargets
        val set = fixture("wifi-standard-current")
        listOf(t.emit, t.delegateEmit, t.collect, t.resume,
            io.github.YGHFv.HyperExtend.hook.feature.systemui.ForceBlurTargets.application).forEach { method(set, it) }
        t.fields.forEach { (owner, name, type) -> field(set, owner, name, type) }
        assertEquals("kotlinx.coroutines.flow.FlowCollector", t.fields[1].third)
        assertEquals(16, set.size)
    }

    @Test fun listDispatchAndDefaultsAreCompleteWithoutEagerStaticReads() {
        val t = IconBlockListTargets
        val set = fixture("icon-block-list-current")
        (listOf(t.setBlockList, t.addIgnored) + t.callers).forEach { method(set, it) }
        field(set, t.MANAGER, "mGroup", "android.view.ViewGroup")
        field(set, t.MANAGER, "mLocation", "com.android.systemui.statusbar.phone.StatusBarLocation")
        for (name in listOf("RIGHT_BLOCK_LIST", "CONTROL_CENTER_BLOCK_LIST", "MINI_RIGHT_BLOCK_LIST"))
            field(set, t.DEFAULTS, name, "java.util.ArrayList")
        assertEquals(25, set.size)
    }

    @Test fun notificationFixtureIncludesFullCopyConstructorAndNativeConsumers() {
        val t = NotificationIconLimitTargets
        val set = fixture("notification-icon-limit-current")
        (listOf(t.emit, t.collection) + t.sourceBindings + t.nativeLayout).forEach { method(set, it) }
        t.fields.forEach { (owner, name, type) -> field(set, owner, name, type) }
        assertTrue(descriptor(t.DATA) + "-><init>(Ljava/util/List;I" + descriptor("${t.DATA}\$LimitType") + ")V" in set)
        assertEquals(18, set.size)
    }

    @Test fun mobileAndOperatorFixturesIncludeExactMergedClassesAndFreshConfigProducer() {
        val t = MobileMarkerTargets
        val set = fixture("mobile-markers-current")
        (t.emitters + t.collectors + t.pairAccess).forEach { method(set, it) }
        t.fields.forEach { (owner, name, type) -> field(set, owner, name, type) }
        assertTrue("Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V" in set)
        val op = OperatorIconTargets
        val operator = fixture("operator-icons-current")
        (listOf(op.create) + op.callers).forEach { method(operator, it) }
        op.fields.forEach { (owner, name, type) -> field(operator, owner, name, type) }
        assertEquals(29, set.size)
        assertEquals(13, operator.size)
    }
}
