/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FlashlightStrengthSettings
import io.github.YGHFv.HyperExtend.core.featureById
import org.junit.Assert.*
import org.junit.Test

class FlashlightStrengthTest {
    @Test fun featureIsOptInAndDoesNotPromiseLiveSliderTakeover() {
        val feature = featureById(FlashlightStrengthSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        // 两档共存：控制方式 + 强度上限。上游那三种「写节点模式」在本机没有对应物，
        // 因为它们描述的是「怎么写同一个节点」，而本机根本没有那个节点。
        assertEquals(listOf(FlashlightStrengthSettings.MODE, FlashlightStrengthSettings.PERCENT),
            feature.config.map { it.key })
        assertTrue(feature.requirement!!.contains("亮度条实时控制"))
        assertTrue(feature.requirement.contains("手电筒开关仍由系统手电筒磁贴控制"))
        assertTrue(feature.requirement.contains("不写节点"))
        assertTrue(feature.title.contains("手电筒亮度控制"))
    }
    @Test fun modeSelectionIsTwoNotNullableTabs() {
        val s = FlashlightStrengthSettings
        assertTrue(s.isLive(s.MODE_LIVE))
        assertFalse(s.isLive(s.MODE_CEILING))
        // 未知/空值归一化到出厂档（开启时限制上限），避免写坏的设置把亮度条整个接管。
        assertEquals(s.MODE_CEILING, s.normalize(""))
        assertEquals(s.MODE_CEILING, s.normalize("9"))
        assertEquals(s.MODE_CEILING, s.normalize(s.MODE_CEILING))
        assertEquals(s.MODE_LIVE, s.normalize(s.MODE_LIVE))
        assertEquals(2, s.style.entries.size)
    }
    @Test fun liveHijackRequiresTorchAlreadyOnAndOwner() {
        val p = FlashlightStrengthPolicy
        assertTrue(p.hijack(true, true, true, true, false, true))
        // 手电筒没开时接管亮度条 = 用户既没灯也调不了亮度，必须放行原生。
        assertFalse(p.hijack(true, true, false, true, false, true))
        assertFalse(p.hijack(true, false, true, true, false, true))
        assertFalse(p.hijack(false, true, true, true, false, true))
        assertFalse(p.hijack(true, true, true, false, false, true))
        assertFalse(p.hijack(true, true, true, true, true, true))
        assertFalse(p.hijack(true, true, true, true, false, false))
    }
    @Test fun sliderValueMapsToStrengthThroughHostCurveAndCeiling() {
        val p = FlashlightStrengthPolicy
        // 线性曲线：滑块取满 → 100，取 0 → 1（0 强度对手电筒没有意义，夹到下限 1）。
        val linear = { v: Int, _: Float, _: Float -> v / 100f }
        assertEquals(100, p.strength(100, 0f, 1f, 100, linear))
        assertEquals(50, p.strength(50, 0f, 1f, 100, linear))
        assertEquals(1, p.strength(0, 0f, 1f, 100, linear))
        // 上限只降不升。
        assertEquals(30, p.strength(100, 0f, 1f, 30, linear))
        // 非法输入一律 null，让调用方放行原生分支。
        assertNull(p.strength(100, 1f, 1f, 100, linear))
        assertNull(p.strength(100, 0f, 1f, 0, linear))
        assertNull(p.strength(100, 0f, 1f, 101, linear))
        assertNull(p.strength(100, Float.NaN, 1f, 100, linear))
        assertNull(p.strength(100, 0f, 1f, 100) { _, _, _ -> Float.NaN })
        assertNull(p.strength(100, 0f, 1f, 100) { _, _, _ -> throw IllegalStateException("host") })
    }
    @Test fun neverRaiseNativeStrengthIncludingSuperFlashFiftyPercentPolicy() {
        val p = FlashlightStrengthPolicy
        assertEquals(0.5f, p.cap(0.5f, 100), 0f)
        assertEquals(0.3f, p.cap(0.5f, 30), 0f)
        for (native in 1..100) for (limit in 1..100) {
            val original = native / 100f
            val capped = p.cap(original, limit)
            assertTrue(capped <= original)
            assertTrue(capped <= limit / 100f)
            assertTrue(capped >= 0.01f)
        }
    }
    @Test fun unexpectedNativeValuesAndInvalidConfigurationRemainNative() {
        val p = FlashlightStrengthPolicy
        assertTrue(p.cap(Float.NaN, 20).isNaN())
        for (value in listOf(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 0f, 2f))
            assertEquals(value, p.cap(value, 20), 0f)
        for (percent in listOf(-1, 0, 101, Int.MAX_VALUE)) assertEquals(0.5f, p.cap(0.5f, percent), 0f)
    }
    @Test fun sensorBranchTurningOffAndNativeRestrictionsRemainNative() {
        val p = FlashlightStrengthPolicy
        assertTrue(p.accepts(0, true, 100, 0, false, false, true))
        assertFalse(p.accepts(1, true, 100, 0, false, false, true))
        assertFalse(p.accepts(0, false, 100, 0, false, false, true))
        assertFalse(p.accepts(0, true, 99, 0, false, false, true))
        assertFalse(p.accepts(0, true, 100, 10, false, false, true))
        assertFalse(p.accepts(0, true, 100, 0, true, false, true))
        assertFalse(p.accepts(0, true, 100, 0, false, true, true))
        assertFalse(p.accepts(0, true, 100, 0, false, false, false))
    }
    @Test fun nestedControllerReadersCannotConsumeScopedOverride() {
        val t = FlashlightStrengthTargets
        assertTrue(FlashlightStrengthPolicy.directCaller(listOf(t.RUNNABLE to "run")))
        assertFalse(FlashlightStrengthPolicy.directCaller(listOf(t.CONTROLLER to "doSuperFlashlightStrategy", t.RUNNABLE to "run")))
        assertFalse(FlashlightStrengthPolicy.directCaller(emptyList()))
    }
    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "boolean" -> "Z"; "int" -> "I"; "float" -> "F"
        else -> "L${type.replace('.', '/')};"
    }
    @Test fun exactTargetsAndFieldsHaveCompleteNativeEvidence() {
        val fixture = javaClass.getResourceAsStream("/flashlight-strength-host-members.txt")!!
            .bufferedReader().use { it.readLines().toSet() }
        val t = FlashlightStrengthTargets
        for (method in t.methods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixture)
        }
        for ((owner, name, type) in t.fields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixture)
        // 档二（亮度条实时控制）的载体也逐条钉住：这些是上游做法在本机的替代落点。
        for (method in t.liveMethods) {
            val sig = descriptor(method.owner) + "->" + method.name + "(" +
                method.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(method.returns)
            assertTrue(sig, sig in fixture)
        }
        for ((owner, name, type) in t.brightnessFields + t.torchFields)
            assertTrue(descriptor(owner) + "->" + name + ":" + descriptor(type) in fixture)
        assertEquals(22, fixture.size)
        assertTrue(t.read.isStatic)
        assertTrue(t.gammaToLinear.isStatic)
        // 档一绝不写强度、档二绝不写全局偏好：两条路径的边界都由「解析了哪些方法」固定下来。
        assertTrue(t.methods.none { it.name == "putFloat" || it.name == "turnOnTorchWithStrengthLevel" })
        assertTrue(t.liveMethods.none { it.name == "putFloat" || it.name == "putString" })
    }
}
