/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.hook.feature.systemui.DoubleTapTracker
import org.junit.Assert.*
import org.junit.Test

class StatusBarGesturePolicyTest {
    @Test fun onlyNativeAcceptedVisibleFingerEventsCanParticipate() {
        fun allow(enabled: Boolean = true, blocked: Boolean = false, accepted: Boolean = true,
            attached: Boolean = true, shown: Boolean = true, main: Boolean = true,
            exploring: Boolean = false, finger: Boolean = true, pointers: Int = 1,
            buttons: Int = 0, obscured: Boolean = false, x: Float = 5f, y: Float = 5f) =
            StatusBarGesturePolicy.eligible(enabled, blocked, accepted, attached, shown, main,
                exploring, finger, pointers, buttons, obscured, x, y, 100, 20)
        assertTrue(allow())
        assertFalse(allow(enabled = false)); assertFalse(allow(blocked = true))
        assertFalse(allow(accepted = false)); assertFalse(allow(attached = false))
        assertFalse(allow(shown = false)); assertFalse(allow(main = false))
        assertFalse(allow(exploring = true)); assertFalse(allow(finger = false))
        assertFalse(allow(pointers = 2)); assertFalse(allow(buttons = 1))
        assertFalse(allow(obscured = true)); assertFalse(allow(x = Float.NaN))
        assertFalse(allow(x = -1f)); assertFalse(allow(x = 100f))
        assertFalse(allow(y = 20f)); assertFalse(allow(y = Float.POSITIVE_INFINITY))
    }
    @Test fun historicalDragAndDetachCancelAnOtherwiseStationaryPair() {
        val tracker = DoubleTapTracker(50f, 300, 8f)
        tracker.down(10, 5f, 5f); assertFalse(tracker.up(30, 5f, 5f))
        tracker.down(100, 5f, 5f)
        tracker.move(30f, 5f)
        assertFalse(tracker.up(120, 5f, 5f))
        tracker.down(160, 5f, 5f); assertFalse(tracker.up(180, 5f, 5f))
        tracker.reset()
        tracker.down(200, 5f, 5f); assertFalse(tracker.up(220, 5f, 5f))
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "float" -> "F"; "long" -> "J"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun nativeTouchAndPowerActionUseFullSignaturesNotOneArgumentMatching() {
        val signatures = javaClass.getResourceAsStream("/statusbar-double-tap-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = StatusBarGestureTargets
        for (method in listOf(t.touch, t.dispatch, t.sleep) + t.callers) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in signatures)
        }
        assertTrue(descriptor(t.OWNER) + "->mTouchEventHandler:" + descriptor(t.HANDLER) in signatures)
        assertEquals(8, signatures.size)
        assertEquals(listOf("long"), t.sleep.parameters)
        assertFalse(t.sleep.isStatic)
        assertEquals("onTouchEvent", t.touch.name)
    }
}
