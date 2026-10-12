/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.LockscreenShortcutSettings
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import org.junit.Assert.*
import org.junit.Test

class LockscreenFlashlightPolicyTest {
    private fun press() = FlashlightPress().apply { down(100, 10f, 20f, 7) }
    @Test fun leftButtonIsNowOneThreeWayChoiceInsteadOfTwoCompetingCards() {
        // 合并前「隐藏」与「手电筒」是两张可以同时打开的卡片，靠隐藏优先收场；
        // 合并后是同一个选择项的取值，互斥由数据保证。
        assertEquals(LockscreenShortcutSettings.MODE_DEFAULT, LockscreenShortcutSettings.modeOf(""))
        assertEquals(LockscreenShortcutSettings.MODE_DEFAULT, LockscreenShortcutSettings.modeOf("bad"))
        assertEquals(LockscreenShortcutSettings.MODE_HIDE, LockscreenShortcutSettings.modeOf("1"))
        assertEquals(LockscreenShortcutSettings.MODE_FLASHLIGHT, LockscreenShortcutSettings.modeOf("2"))
        assertTrue(LockscreenShortcutSettings.hidesLeft("1"))
        assertFalse(LockscreenShortcutSettings.hidesLeft("2"))
        assertTrue(LockscreenShortcutSettings.replacesLeft("2"))
        assertFalse(LockscreenShortcutSettings.replacesLeft("1"))
        // 「保持原生」档既不算隐藏也不算替换。
        for (raw in listOf("", "0", "bad")) {
            assertFalse(raw, LockscreenShortcutSettings.hidesLeft(raw))
            assertFalse(raw, LockscreenShortcutSettings.replacesLeft(raw))
        }
        // 三选一的两档不可能同时成立。
        for (raw in listOf("0", "1", "2", "")) {
            assertFalse(raw, LockscreenShortcutSettings.hidesLeft(raw) && LockscreenShortcutSettings.replacesLeft(raw))
        }
    }
    @Test fun shortTapNeverToggles() { assertFalse(press().up(499)) }
    @Test fun thresholdIsInclusive() { assertTrue(press().up(500)) }
    @Test fun toggleOnlyOncePerStream() {
        val p = press(); assertTrue(p.up(500)); assertFalse(p.up(600)); assertFalse(p.captured)
    }
    @Test fun backwardsTimeDoesNotToggle() { assertFalse(press().up(99)) }
    @Test fun noDownMeansNoToggle() { assertFalse(FlashlightPress().up(999)) }
    @Test fun movementAtSlopIsAllowed() {
        val p = press(); p.move(16f, 28f, 7, 1, 10f); assertTrue(p.up(500))
    }
    @Test fun movingAwayAndBackStaysCancelled() {
        val p = press(); p.move(40f, 20f, 7, 1, 10f); p.move(10f, 20f, 7, 1, 10f)
        assertTrue(p.captured); assertFalse(p.up(600))
    }
    @Test fun pointerReplacementCancels() {
        val p = press(); p.move(10f, 20f, 8, 1, 10f); assertFalse(p.up(600))
    }
    @Test fun multiTouchCancelsEvenIfFirstFingerRemains() {
        val p = press(); p.move(10f, 20f, 7, 2, 10f); p.move(10f, 20f, 7, 1, 10f); assertFalse(p.up(600))
    }
    @Test fun invalidCoordinatesAndSlopCancel() {
        for ((x, slop) in listOf(Float.NaN to 10f, Float.POSITIVE_INFINITY to 10f, 10f to -1f, 10f to Float.NaN)) {
            val p = press(); p.move(x, 20f, 7, 1, slop); assertFalse(p.up(600))
        }
    }
    @Test fun cancellationKeepsCaptureUntilTerminalEvent() {
        val p = press(); p.cancel(); assertTrue(p.captured); assertFalse(p.up(600)); assertFalse(p.captured)
    }
    @Test fun newDownResetsOldCancelledStream() {
        val p = press(); p.cancel(); p.down(200, 0f, 0f, 0); assertTrue(p.up(600))
    }
    @Test fun detachAndRebindResetPendingPress() {
        val p = press(); p.reset(); assertFalse(p.up(600))
    }
    @Test fun terminalEventsReleaseOuterHostMovementFlags() {
        assertFalse(FlashlightPress.continues(1)); assertFalse(FlashlightPress.continues(3))
        for (action in listOf(0, 2, 5, 6)) assertTrue(FlashlightPress.continues(action))
    }
    @Test fun ownedDrawableRestoresOriginalIdentity() {
        val s = ShortcutOwnedValue<Any>(); val native = Any(); val ours = Any()
        assertSame(ours, s.apply(native, ours)); s.apply(ours, ours); assertSame(native, s.restore(ours))
    }
    @Test fun nativeUpdateIsNotOverwrittenOnExit() {
        val s = ShortcutOwnedValue<Any>(); val newNative = Any()
        s.apply(Any(), Any()); assertSame(newNative, s.restore(newNative))
    }
    @Test fun observedHostRebindBecomesNewBaseline() {
        val s = ShortcutOwnedValue<Any>(); val oldIcon = Any(); val newIcon = Any(); val newNative = Any()
        s.apply(Any(), oldIcon); s.apply(newNative, newIcon); assertSame(newNative, s.restore(newIcon))
    }
    @Test fun nullTintAndDescriptionAreRestored() {
        val s = ShortcutOwnedValue<Any>(); val ours = Any()
        s.apply(null, ours); assertNull(s.restore(ours))
        val nativeTint = Any(); s.apply(nativeTint, null); s.apply(null, null); assertSame(nativeTint, s.restore(null))
    }
    @Test fun uiDescribesBoundariesAndNeverEnablesByDefault() {
        val f = featureById(LockscreenShortcutSettings.FEATURE)!!
        assertFalse(f.defaultEnabled)
        // 合并后不再有「优先隐藏」这条隐藏规则：处理方式是互斥的三选一。
        assertTrue(f.requirement!!.contains("按住至少 0.4 秒"))
        assertTrue(f.requirement.contains("不含上游缩放动画"))
        assertTrue(f.summary.contains("手电筒"))
        assertTrue(f.hasDetailPage)
        assertEquals(listOf(LockscreenShortcutSettings.MODE), f.config.map { it.key })
    }
    @Test fun allNewTargetsAreBackedByHostSignatures() {
        fun descriptor(name: String): String = when (name) {
            "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"; else -> "L${name.replace('.', '/')};"
        }
        val fixture = javaClass.getResourceAsStream("/lockscreen-flashlight-host-methods.txt")!!.bufferedReader().use { it.readLines().toSet() }
        for (spec in LockscreenFlashlightTargets.queries + LockscreenFlashlightTargets.pluginQueries +
            LockscreenFlashlightTargets.callers + LockscreenFlashlightTargets.hostCallers + LockscreenFlashlightTargets.touch) {
            val signature = descriptor(spec.owner) + "->" + spec.name + "(" +
                spec.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(spec.returns)
            assertTrue(signature, signature in fixture)
        }
    }
}
