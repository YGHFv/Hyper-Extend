/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.hook.HookInvocation
import io.github.YGHFv.HyperExtend.hook.feature.systemui.DoubleTapTracker
import org.junit.Assert.*
import org.junit.Test

/** 模拟原生第二次按下就启动滚动、模块在第二次抬起才息屏的真实先后顺序。 */
class StatusBarDoubleTapScopeTest {
    @Test fun onlyMatchingDetectorAndEventAreSuppressedDuringNativeDispatch() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val event = Any()
        assertFalse(scope.suppresses(detector, event, true, false))
        assertEquals(7, scope.during(detector, event, true) {
            assertTrue(scope.suppresses(detector, event, true, false))
            assertFalse(scope.suppresses(Any(), event, true, false))
            assertFalse(scope.suppresses(detector, Any(), true, false))
            assertFalse(scope.suppresses(detector, event, false, false))
            assertFalse(scope.suppresses(detector, event, true, true))
            7
        })
        assertFalse(scope.suppresses(detector, event, true, false))
    }

    @Test fun disabledIneligibleAndMissingDetectorDoNotReserveNativeAction() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val event = Any()
        for ((owner, sample, eligible) in listOf(
                Triple(detector, event, false), Triple(null, event, true), Triple(detector, null, true))) {
            scope.during(owner, sample, eligible) {
                assertFalse(scope.suppresses(detector, event, true, false))
            }
        }
    }

    @Test fun nestedDispatchRestoresOuterIdentityAndIneligibleInnerDoesNotBorrowIt() {
        val scope = StatusBarDoubleTapScope()
        val outer = Any()
        val inner = Any()
        val first = Any()
        val second = Any()
        scope.during(outer, first, true) {
            scope.during(inner, second, true) {
                assertTrue(scope.suppresses(inner, second, true, false))
                assertFalse(scope.suppresses(outer, first, true, false))
            }
            assertTrue(scope.suppresses(outer, first, true, false))
            scope.during(inner, second, false) {
                assertFalse(scope.suppresses(outer, first, true, false))
                assertFalse(scope.suppresses(inner, second, true, false))
            }
            assertTrue(scope.suppresses(outer, first, true, false))
        }
    }

    @Test fun nativeExceptionClearsScopeAndDoesNotReplayTouchDispatch() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val event = Any()
        val invocation = HookInvocation()
        val failure = IllegalStateException("原生触摸失败")
        var calls = 0
        val native = { calls++; throw failure }
        val caught = runCatching {
            invocation.protect({
                scope.during(detector, event, true) { invocation.proceed(native) }
            }, native, {})
        }.exceptionOrNull()
        assertSame(failure, caught)
        assertEquals(1, calls)
        assertFalse(scope.suppresses(detector, event, true, false))
    }

    @Test fun otherThreadCannotConsumeCurrentTouchOrAsynchronousScroll() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val event = Any()
        var consumed = true
        scope.during(detector, event, true) {
            val other = Thread { consumed = scope.suppresses(detector, event, true, false) }
            other.start()
            other.join()
            assertTrue(scope.suppresses(detector, event, true, false))
        }
        assertFalse(consumed)
    }

    private data class Actions(val touchCalls: Int, val scrolls: Int, val sleeps: Int, val childCalls: Int)

    private fun doubleTap(enabled: Boolean = true, blocked: Boolean = false,
        eligible: Boolean = true, sleepFails: Boolean = false, dragged: Boolean = false): Actions {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val tracker = DoubleTapTracker(50f, 300, 8f)
        var touches = 0
        var scrolls = 0
        var sleeps = 0
        var children = 0
        for ((index, time) in listOf(10L, 30L, 100L, 120L).withIndex()) {
            val event = Any()
            val reserve = enabled && !blocked && eligible
            scope.during(detector, event, reserve) {
                touches++
                // 原生 GestureDetector 在第二次 DOWN、UP 都会回调；只拦截双击副作用。
                if (index >= 2 && !scope.suppresses(detector, event, enabled, blocked)) scrolls++
                children++
                true
            }
            if (reserve) {
                if (index % 2 == 0) tracker.down(time, 5f, 5f)
                else {
                    if (dragged && index == 3) tracker.move(30f, 5f)
                    if (tracker.up(time, 5f, 5f)) {
                        runCatching { if (sleepFails) error("息屏失败") else sleeps++ }
                    }
                }
            } else tracker.reset()
        }
        return Actions(touches, scrolls, sleeps, children)
    }

    @Test fun doubleTapOnlySleepsAndCannotLaunchScrollBeforeSecondUp() {
        assertEquals(Actions(4, 0, 1, 4), doubleTap())
    }

    @Test fun disabledSafeModeAndExcludedTouchKeepOriginalDoubleTap() {
        for (result in listOf(doubleTap(enabled = false), doubleTap(blocked = true), doubleTap(eligible = false))) {
            assertEquals(Actions(4, 2, 0, 4), result)
        }
    }

    @Test fun sleepFailureDoesNotReplayAlreadySuppressedScroll() {
        assertEquals(Actions(4, 0, 0, 4), doubleTap(sleepFails = true))
    }

    @Test fun dragDoesNotSleepAndNativeTouchStillRunsExactlyOncePerEvent() {
        assertEquals(Actions(4, 0, 0, 4), doubleTap(dragged = true))
    }

    @Test fun nativeSingleTapRemainsOutsideDoubleTapCallback() {
        val scope = StatusBarDoubleTapScope()
        val detector = Any()
        val event = Any()
        var singles = 0
        // 尚未认领的单击不被作用域拦截；已认领的跟随系统单击由统一确认器调用原生工具。
        scope.during(detector, event, true) { singles++ }
        assertEquals(1, singles)
        assertFalse(scope.suppresses(detector, event, true, false))
    }

    @Test fun hostEvidenceProvesNativeDoubleCallbackSchedulesScrollBeforeModulePostprocessing() {
        val flow = javaClass.getResourceAsStream("/statusbar-double-tap-scroll-host-flow.txt")!!
            .bufferedReader().use { it.readText() }
        assertTrue(flow.contains("0x0020 invoke-virtual"))
        assertTrue(flow.contains("GestureDetector;->onTouchEvent"))
        assertTrue(flow.contains("0x0005 invoke-virtual v0, v1"))
        assertTrue(flow.contains("StatusBarClickTool;->invokeInputManager(Z)V"))
        assertTrue(flow.contains("0x0089 invoke-static"))
        assertTrue(flow.contains("BuildersKt;->launch\$default"))
        assertTrue(flow.contains("0x001f invoke-virtual"))
        assertTrue(flow.contains("Method;->invoke"))
        assertTrue(flow.contains("scrollToTop"))
        assertTrue(flow.contains("onSingleTapConfirmed"))
    }

    @Test fun featureDescriptionDocumentsMutualExclusionAndRestoration() {
        val description = featureById("status_bar_double_tap")!!.requirement!!
        assertTrue(description.contains("阻止同时滚动到页面顶部"))
        assertTrue(description.contains("保留单击"))
        assertTrue(description.contains("关闭/安全模式恢复原生双击处理"))
    }
}
