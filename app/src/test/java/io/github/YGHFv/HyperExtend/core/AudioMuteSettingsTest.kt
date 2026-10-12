/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class AudioMuteSettingsTest {
    @Test fun snowLeopardMapsOntoAnIndependentMuteCard() {
        val feature = featureById(AudioMuteSettings.FEATURE)!!
        assertEquals("systemui_snow_leopard_mute", AudioMuteSettings.FEATURE)
        assertEquals("静音模式", feature.title)
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui"), feature.scopes)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertTrue(feature.requirement!!.contains("重启系统界面"))
        assertTrue(feature.origin.contains("HyperCeiler"))
    }

    @Test fun itIsATileNotAConfigPage() {
        // 磁贴的 on/off 就是主静音状态本身，没有第二个实现方式可以选，所以没有详情页。
        val feature = featureById(AudioMuteSettings.FEATURE)!!
        assertFalse(feature.hasDetailPage)
        assertTrue(feature.options.isEmpty())
        assertTrue(feature.config.isEmpty())
        assertNull(feature.configKey)
    }

    @Test fun specNeverCollidesWithUpstreamTileNames() {
        // 宿主里没有同名磁贴，也不与其它模块自建磁贴重名。
        assertNotEquals("custom_SnowLeopardMode", AudioMuteSettings.SPEC)
        assertEquals("hyperextend_mute", AudioMuteSettings.SPEC)
        assertNotEquals(SunlightTileTestSpec.SUNLIGHT, AudioMuteSettings.SPEC)
        assertNotEquals(SunlightTileTestSpec.TAPLUS, AudioMuteSettings.SPEC)
    }

    @Test fun upstreamRootOnlyBehaviourIsNotPromisedInText() {
        val requirement = featureById(AudioMuteSettings.FEATURE)!!.requirement!!
        // 文案必须写明这不是上游那个「杀 HAL + chmod 节点」的实现。
        assertTrue(requirement.contains("chmod"))
        assertTrue(requirement.contains("不落盘"))
        assertTrue(requirement.contains("MODIFY_AUDIO_ROUTING"))
    }
}

/** 只放常量，避免测试依赖宿主侧的对象。 */
private object SunlightTileTestSpec {
    const val SUNLIGHT = "hyperextend_sunlight"
    const val TAPLUS = "hyperextend_taplus"
}
