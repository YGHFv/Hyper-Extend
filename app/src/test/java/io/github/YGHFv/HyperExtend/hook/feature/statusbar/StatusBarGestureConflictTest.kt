/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.StatusBarGestureSettings.Action
import org.junit.Assert.*
import org.junit.Test

class StatusBarGestureConflictTest {
    @Test fun nativeSingleBeforeSecondUpAndAfterSleepBothRemainSuppressed() {
        val scope = StatusBarDoubleTapScope()
        val tracker = StatusBarTapTracker(50f, 8f, 300)
        val detector = Any()
        var scrolls = 0
        val actions = mutableListOf<Boolean>()
        scope.reserveTap(detector, 10)
        tracker.down(10, 10, 5f, 5f)
        tracker.up(30, 10, 5f, 5f)
        scope.reserveTap(detector, 100)
        tracker.down(100, 100, 5f, 5f)
        // 原生最短双击间隔、距离判定或消息时点不同，可能在模块第二次 UP 前就确认了单击。
        if (!scope.suppressesConsumedTap(detector, 10, true, false)) scrolls++
        assertNull(tracker.expire(330))
        tracker.up(350, 100, 5f, 5f)?.let {
            scope.consumePair(detector, it.firstDown, it.downTime)
            actions += it.double
        }
        for (down in listOf(10L, 100L)) {
            if (!scope.suppressesConsumedTap(detector, down, true, false)) scrolls++
        }
        assertEquals(listOf(true), actions)
        assertEquals(0, scrolls)
    }

    @Test fun everyCustomDoubleActionIsExclusiveAndRunsOnce() {
        for (action in Action.entries.filter { it != Action.DEFAULT }) {
            val scope = StatusBarDoubleTapScope()
            val tracker = StatusBarTapTracker(50f, 8f, 300)
            val detector = Any()
            var scrolls = 0
            val executed = mutableListOf<Action>()
            for ((index, time) in listOf(10L, 30L, 100L, 120L).withIndex()) {
                val down = if (index < 2) 10L else 100L
                val event = Any()
                if (index % 2 == 0) scope.reserveTap(detector, down)
                scope.during(detector, event, true) {
                    if (index >= 2 && !scope.suppresses(detector, event, true, false)) scrolls++
                }
                val trigger = if (index % 2 == 0) tracker.down(time, down, 5f, 5f)
                    else tracker.up(time, down, 5f, 5f)
                trigger?.let { if (action != Action.DISABLED) executed += action }
            }
            assertNull(tracker.expire(1000))
            assertEquals(action.title, 0, scrolls)
            assertEquals(if (action == Action.DISABLED) emptyList<Action>() else listOf(action), executed)
        }
    }

    @Test fun actionFailureNeverFallsThroughToNativeScroll() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        scope.reserveTap(detector, 10)
        scope.reserveTap(detector, 100)
        runCatching { error("息屏或其他自定义动作失败") }
        assertTrue(scope.suppressesConsumedTap(detector, 10, true, false))
        assertTrue(scope.suppressesConsumedTap(detector, 100, true, false))
    }

    @Test fun disabledOrSafeModeClearsReservationAndRestoresOriginal() {
        for ((enabled, blocked) in listOf(false to false, true to true)) {
            val scope = StatusBarDoubleTapScope()
            val detector = Any()
            scope.reserveTap(detector, 10)
            assertFalse(scope.suppressesConsumedTap(detector, 10, enabled, blocked))
            assertFalse(scope.suppressesConsumedTap(detector, 10, true, false))
        }
    }

    @Test fun reservationsNeverAffectOtherDetectorsOrUnclaimedClicksAndStayBounded() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        scope.reserveTap(null, 10)
        scope.reserveTap(detector, -1)
        for (down in 0L..19L) scope.reserveTap(detector, down)
        assertFalse(scope.suppressesConsumedTap(detector, 0, true, false))
        assertTrue(scope.suppressesConsumedTap(detector, 19, true, false))
        assertFalse(scope.suppressesConsumedTap(Any(), 19, true, false))
        assertFalse(scope.suppressesConsumedTap(detector, 100, true, false))
    }

    @Test fun hostDisableBitsAndIslandOccupancyKeepNativeGates() {
        assertTrue(StatusBarGestureHostGate.permits(0, false))
        assertTrue(StatusBarGestureHostGate.permits(0x800000, false))
        assertTrue(StatusBarGestureHostGate.permits(0x100000, false))
        assertTrue(StatusBarGestureHostGate.permits(0x20000, false))
        assertFalse(StatusBarGestureHostGate.permits(0x920000, false))
        assertFalse(StatusBarGestureHostGate.permits(0, true))
    }

    @Test fun broadcastsUseExistingHostActionsRatherThanNewExportedReceivers() {
        val mapped = Action.entries.mapNotNull { StatusBarGestureActions.broadcast(it) }
        assertEquals(setOf("SYSTEM_ACTION_NOTIFICATIONS", "SYSTEM_ACTION_QUICK_SETTINGS",
            "SYSTEM_ACTION_TAKE_SCREENSHOT", "SYSTEM_ACTION_BACK", "SYSTEM_ACTION_HOME",
            "SYSTEM_ACTION_RECENTS"), mapped.toSet())
        assertNull(StatusBarGestureActions.broadcast(Action.SLEEP))
        assertNull(StatusBarGestureActions.broadcast(Action.SCROLL_TOP))
    }

    @Test fun customActionsAndHostGateUseExactEvidenceMembers() {
        fun descriptor(type: String) = when (type) {
            "void" -> "V"; "boolean" -> "Z"
            else -> "L${type.replace('.', '/')};"
        }
        val members = javaClass.getResourceAsStream("/statusbar-custom-actions-host-members.txt")!!
            .bufferedReader().use { it.readLines().filterNot { line -> line.startsWith("#") }.toSet() }
        val t = StatusBarGestureTargets
        for (method in listOf(t.nativeClick, t.stateValue, t.pairFirst, t.lazyGet, t.inputInstance,
            t.scrollTop, t.floatingWindow)) {
            val signature = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(signature, signature in members)
        }
        assertTrue(t.inputInstance.isStatic)
        assertTrue(t.floatingWindow.isStatic)
        assertFalse(t.scrollTop.isStatic)
    }

    @Test fun dexFlowIncludesDeferredTapAndPermissionProtectedSystemActionReceiver() {
        val flow = javaClass.getResourceAsStream("/statusbar-custom-actions-host-flow.txt")!!
            .bufferedReader().use { it.readText() }
        for (action in Action.entries.mapNotNull { StatusBarGestureActions.broadcast(it) })
            assertTrue(action, flow.contains(action))
        assertTrue(flow.contains("0x0040 invoke-interface"))
        assertTrue(flow.contains("onSingleTapConfirmed"))
        assertTrue(flow.contains("com.android.systemui.permission.SELF"))
        assertTrue(flow.contains("registerReceiverForAllUsers"))
        assertTrue(flow.contains("animateExpandSettingsPanel"))
        assertTrue(flow.contains("ScreenshotHelper;->takeScreenshot"))
    }
}
