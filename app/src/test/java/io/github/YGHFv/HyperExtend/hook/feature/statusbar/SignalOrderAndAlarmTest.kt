/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.HyperChoice
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.hook.feature.systemui.SystemUiMethod
import org.junit.Assert.*
import org.junit.Test

class SignalOrderAndAlarmTest {
    @Test fun freshSignalOrderKeepsEverySlotAndDoesNotDuplicateNeighboringIcons() {
        val native = listOf("hotspot", "hd", "airplane", "mobile", "demo_mobile", "no_sim", "wifi", "demo_wifi")
        val order = SignalOrderPolicy.permutation(native, false)!!
        assertEquals(listOf("hotspot", "hd", "airplane", "wifi", "demo_wifi", "no_sim", "mobile", "demo_mobile"),
            order.map { native[it] })
        assertEquals(native.indices.toSet(), order.toSet())
        assertNull(SignalOrderPolicy.permutation(native, true))
        assertNull(SignalOrderPolicy.permutation(native + "wifi", false))
        assertNull(SignalOrderPolicy.permutation(native - "no_sim", false))
        assertNull(SignalOrderPolicy.permutation(order.map { native[it] }, false))
    }

    @Test fun alarmThresholdHonorsToleranceAndUnknownOrStaleReadingsKeepNative() {
        val now = 1_000_000L
        val edge = now + 3_600_000 + 59_000
        assertFalse(AlarmWindowPolicy.hide(edge, now, 1, 10_000, 10_000))
        assertTrue(AlarmWindowPolicy.hide(edge + 1, now, 1, 10_000, 10_000))
        for (trigger in listOf(null, -1L, now - 1, now))
            assertFalse(AlarmWindowPolicy.hide(trigger, now, 1, 10_000, 10_000))
        for (hours in listOf(-1, 0, 49, Int.MAX_VALUE))
            assertFalse(AlarmWindowPolicy.hide(edge + 1, now, hours, 10_000, 10_000))
        assertFalse(AlarmWindowPolicy.hide(edge + 1, now, 1, 10_000, 45_001))
        assertFalse(AlarmWindowPolicy.hide(edge + 1, now, 1, 10_000, 9_999))
        assertTrue(AlarmWindowPolicy.hide(Long.MAX_VALUE, now, 48, 10_000, 10_000))
    }

    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun method(target: SystemUiMethod) = descriptor(target.owner) + "->" + target.name +
        "(" + target.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(target.returns)
    private fun fixture(name: String) = javaClass.getResourceAsStream("/$name-host-members.txt")!!
        .bufferedReader().use { it.readLines().toSet() }

    @Test fun fullFactoryLifecycleAndAlarmDispatchSignaturesMatchTheSavedFixture() {
        val signal = fixture("signal-order-current")
        val s = SignalOrderTargets
        for (target in listOf(s.factory, s.caller)) assertTrue(method(target) in signal)
        for ((owner, name, type) in s.fields) assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in signal)
        assertTrue(s.factory.isStatic)
        assertEquals(7, signal.size)
        val alarm = fixture("alarm-window-current")
        val a = AlarmWindowTargets
        for (target in listOf(a.visible, a.capture, a.user, a.userContext) + a.callers)
            assertTrue(method(target) in alarm)
        for ((owner, name, type) in a.fields) assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in alarm)
        assertEquals(16, alarm.size)
        assertEquals(7, a.callers.size)
    }

    @Test fun onlyAlarmChoiceAddsTimingAndSignalOrderIsAnActualDefaultOffFeature() {
        val icons = featureById("status_bar_icons")!!
        val alarm = icons.config.filterIsInstance<HyperChoice>().single { it.key == "status_bar_icons.alarm_clock" }
        assertTrue(alarm.entries.any { it.variant == "3" })
        val vpn = icons.config.filterIsInstance<HyperChoice>().single { it.key == "status_bar_icons.vpn" }
        assertFalse(vpn.entries.any { it.variant == "3" })
        assertFalse(featureById("status_bar_signal_order")!!.defaultEnabled)
    }
}
