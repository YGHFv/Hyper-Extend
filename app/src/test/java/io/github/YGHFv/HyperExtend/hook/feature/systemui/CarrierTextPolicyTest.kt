/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.CarrierTextSettings
import io.github.YGHFv.HyperExtend.core.CONFIG_KEYS
import io.github.YGHFv.HyperExtend.core.MergedCardMigration
import io.github.YGHFv.HyperExtend.core.HyperChoice
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import org.junit.Assert.*
import org.junit.Test

class CarrierTextPolicyTest {
    private fun transform(mode: String, count: Int?, slot: Int?, text: String?, name: String?) =
        CarrierTextPolicy.transform(CarrierTextSettings.modeOf(mode), count, slot, text, name)

    @Test fun separatorIsStrippedForBothSpacings() {
        assertEquals("中国移动中国联通", transform("1", 2, 0, "中国移动 | 中国联通", null))
        assertEquals("中国移动中国联通", transform("1", 2, 0, "中国移动  |  中国联通", null))
        // 单卡本来就没有分隔符，改写后必须与原文一致（拦截体会因此直接走 proceed）。
        assertEquals("中国移动", transform("1", 1, 0, "中国移动", null))
    }

    @Test fun emptyTextIsNeverRewrittenIntoSomething() {
        // 宿主的「无运营商 / 无服务」路径靠空串走 GONE；替它塞内容等于伪造显示状态。
        assertNull(transform("1", 2, 0, "", null))
        assertNull(transform("1", 2, 0, null, null))
        assertNull(transform("1", 2, 0, "", "手机"))
    }

    @Test fun deviceNameOnlyReplacesTheFirstCard() {
        assertEquals("我的手机", transform("3", 1, 0, "中国移动", "我的手机"))
        assertEquals("我的手机", transform("3", 2, 0, "中国移动", "我的手机"))
        // 第二格清空而不是重复一次设备名：两格都显示同一个名字是重复信息。
        assertEquals("", transform("3", 2, 1, "中国联通", "我的手机"))
    }

    @Test fun deviceNameStillFillsThePrimarySlotWhenTheHostSendsNoText() {
        // 只有 SIM2 在用时的第一次回调：生产方对两张卡用同一个 count 去重，
        // 跟着数量走的格位会漏掉第二次回调，所以这里刻意仍然把名字写进 0 号卡槽。
        // 与「空文本不替宿主塞内容」不矛盾：那条是给分隔符档定的，这一档的语义就是固定占一格。
        assertEquals("我的手机", transform("3", 2, 0, "", "我的手机"))
    }

    @Test fun deviceNameFallsBackToNativeTextWhenUnavailable() {
        for (blank in listOf(null, "", "   ")) {
            assertNull(transform("3", 1, 0, "中国移动", blank))
        }
        // 宿主报「一张卡都没有」时不凭空造出一段原生不会显示的文本。
        assertNull(transform("3", 0, 0, "", "我的手机"))
    }

    @Test fun untrustedCountOrSlotLeavesTheHostValueAlone() {
        for (count in listOf(-1, 3, 99)) assertNull(transform("1", count, 0, "A | B", "n"))
        for (slot in listOf(-1, 2, 5)) assertNull(transform("3", 1, slot, "A", "n"))
        assertNull(transform("1", null, 0, "A | B", null))
        assertNull(transform("1", 1, null, "A", null))
    }

    @Test fun defaultAndHideModesNeverRewriteText() {
        // 隐藏名称只动可见性；把它接到文本上会让宿主自己重算可见性与无障碍标签。
        assertNull(transform("2", 1, 0, "中国移动", "我的手机"))
        assertNull(transform("0", 1, 0, "中国移动", "我的手机"))
        assertNull(transform("9", 1, 0, "中国移动", "我的手机"))
    }

    @Test fun modeChoiceKeepsNativeAsItsDefaultVariant() {
        val row = featureById(CarrierTextSettings.FEATURE)!!.config.single() as HyperChoice
        assertEquals(CarrierTextSettings.MODE, row.key)
        assertTrue(row.key in CONFIG_KEYS)
        assertEquals(CarrierTextSettings.MODE_DEFAULT, row.entries.first().variant)
        assertEquals(
            listOf("0", "1", "2", "3"),
            row.entries.map { it.variant },
        )
        assertEquals(CarrierTextSettings.MODE_DEFAULT, CarrierTextSettings.modeOf(""))
        assertEquals(CarrierTextSettings.MODE_DEFAULT, CarrierTextSettings.modeOf(null))
        assertEquals(CarrierTextSettings.MODE_DEFAULT, CarrierTextSettings.modeOf("7"))
    }

    @Test fun modeOnlySelectsTheExpectedHookLayer() {
        // 文本档与隐藏档挂的是不同的方法；任何一档都不该同时命中两层。
        for (mode in listOf(CarrierTextSettings.MODE_DEFAULT, CarrierTextSettings.MODE_HIDE)) {
            assertFalse(mode, CarrierTextSettings.rewritesText(mode))
        }
        for (mode in listOf(CarrierTextSettings.MODE_SEPARATOR, CarrierTextSettings.MODE_DEVICE_NAME)) {
            assertTrue(mode, CarrierTextSettings.rewritesText(mode))
            assertFalse(mode, CarrierTextSettings.hidesCarrierBlock(mode))
        }
        assertTrue(CarrierTextSettings.hidesCarrierBlock(CarrierTextSettings.MODE_HIDE))
        assertFalse(CarrierTextSettings.hidesSeparator(CarrierTextSettings.MODE_DEVICE_NAME))
        assertTrue(CarrierTextSettings.hidesSeparator(CarrierTextSettings.MODE_SEPARATOR))
    }

    @Test fun legacyHideSwitchMigratesToTheHideMode() {
        val plan = MergedCardMigration.carrierTextModePlan(true, null)!!
        assertEquals(mapOf(CarrierTextSettings.MODE to CarrierTextSettings.MODE_HIDE), plan.configs)
        assertTrue(plan.switches.isEmpty())
    }

    @Test fun explicitModeIsNeverOverwrittenByTheLegacySwitch() {
        for (mode in listOf(CarrierTextSettings.MODE_DEFAULT, CarrierTextSettings.MODE_SEPARATOR,
            CarrierTextSettings.MODE_DEVICE_NAME)) {
            val plan = MergedCardMigration.carrierTextModePlan(true, mode)!!
            assertTrue(mode, plan.configs.isEmpty())
            assertTrue(mode, plan.switches.isEmpty())
        }
    }

    @Test fun offOrUnsetLegacySwitchWritesNothing() {
        // 关着与「从没进过界面」都不能被写成一次显式选择。
        assertNull(MergedCardMigration.carrierTextModePlan(null, null))
        assertNull(MergedCardMigration.carrierTextModePlan(false, null))
        assertNull(MergedCardMigration.carrierTextModePlan(false, CarrierTextSettings.MODE_SEPARATOR))
    }

    @Test fun upgradedCardStillKeepsItsOwnSwitchKey() {
        val feature = featureById(CarrierTextSettings.FEATURE)!!
        assertEquals(CarrierTextSettings.FEATURE, feature.id)
        assertTrue(feature.hasDetailPage)
        assertFalse(feature.defaultEnabled)
        assertTrue(feature.requirement!!.contains("隐私"))
        assertTrue(feature.requirement.contains("重启系统界面"))
        assertTrue(feature.origin.contains("HyperCeiler"))
    }

    private fun descriptor(type: String): String = when (type) {
        "void" -> "V"; "int" -> "I"; "boolean" -> "Z"
        else -> "L${type.replace('.', '/')};"
    }
    private fun method(target: SystemUiMethod) = descriptor(target.owner) + "->" + target.name +
        "(" + target.parameters.joinToString("") { descriptor(it) } + ")" + descriptor(target.returns)
    private fun field(owner: String, name: String, type: String) =
        descriptor(owner) + "->" + name + ":" + descriptor(type)

    /**
     * 三个档的靶点必须逐字存在于当前宿主。留档见同名资源文件，
     * 取证来自 SystemUI 的 classes.dex / classes2.dex 全量 dexdump。
     */
    @Test fun everyTargetMatchesTheSavedHostMembers() {
        val signatures = javaClass.getResourceAsStream("/carrier-text-current-host-members.txt")!!
            .bufferedReader().use { it.readLines().filter(String::isNotBlank).toSet() }
        for (target in CarrierTextTargets.all) assertTrue(method(target), method(target) in signatures)
        assertTrue(method(CarrierTextHooks.headerTarget) in signatures)
        assertTrue(field(CarrierTextTargets.LAYOUT, CarrierTextTargets.SEPARATOR_VIEW, "android.widget.ImageView") in signatures)
        assertTrue(field(CarrierTextTargets.LAYOUT, CarrierTextTargets.SEPARATOR_TEXT, "android.widget.TextView") in signatures)
        assertFalse(CarrierTextTargets.listener.isStatic)
        assertEquals(6, signatures.size)
    }
}
