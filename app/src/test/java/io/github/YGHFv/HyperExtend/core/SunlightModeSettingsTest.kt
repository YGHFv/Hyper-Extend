/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SunlightModeSettingsTest {
    @Test fun bothUpstreamSunshineRowsAreMergedIntoTheSunlightCard() {
        // 上游两行（高亮方式 + 自定义亮度）是同一件事的两种取值，合并成同一张卡片的两个配置行。
        val feature = featureById(SunlightModeSettings.FEATURE)!!
        assertEquals("control_center_sunlight_tile", SunlightModeSettings.FEATURE)
        assertEquals(listOf(SunlightModeSettings.MODE, SunlightModeSettings.BRIGHTNESS),
            feature.config.map { it.key })
        assertTrue(feature.hasDetailPage)
        assertTrue(feature.config[0] is HyperChoice)
        assertTrue(feature.config[1] is HyperSlider)
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        // 三档就是上游那三个 mode；没有第四档，也没有把两行拆成两张卡片。
        assertNull(featureById("system_control_center_sunshine_new_mode_high"))
        assertNull(featureById("system_control_center_sunshine_mode_brightness"))
    }

    @Test fun unknownModesFallBackToSystemDefault() {
        assertEquals(SunlightModeSettings.MODE_SYSTEM, SunlightModeSettings.modeOf(""))
        assertEquals(SunlightModeSettings.MODE_SYSTEM, SunlightModeSettings.modeOf("0"))
        assertEquals(SunlightModeSettings.MODE_SYSTEM, SunlightModeSettings.modeOf("nonsense"))
        assertEquals(SunlightModeSettings.MODE_MAX, SunlightModeSettings.modeOf("2"))
        assertEquals(SunlightModeSettings.MODE_CUSTOM, SunlightModeSettings.modeOf("3"))
        assertFalse(SunlightModeSettings.overridesBrightness(""))
        assertFalse(SunlightModeSettings.overridesBrightness(SunlightModeSettings.MODE_SYSTEM))
        assertTrue(SunlightModeSettings.overridesBrightness(SunlightModeSettings.MODE_MAX))
        assertTrue(SunlightModeSettings.overridesBrightness(SunlightModeSettings.MODE_CUSTOM))
    }

    @Test fun targetBrightnessNeverExceedsTheDeviceRange() {
        // 最大亮度档取设备上限，且**从不**写上游那个 Integer.MAX_VALUE。
        assertEquals(16383, SunlightModeSettings.targetBrightness("2", 2048, 1, 16383))
        assertNotEquals(Int.MAX_VALUE, SunlightModeSettings.targetBrightness("2", 2048, 1, 16383)!!)
        // 自定义档按设备量程夹取：255 量程的机型不会收到 2048。
        assertEquals(255, SunlightModeSettings.targetBrightness("3", 2048, 0, 255))
        assertEquals(100, SunlightModeSettings.targetBrightness("3", 100, 0, 255))
        // 系统默认档不改亮度设置，返回 null 让调用方走原生阳光模式偏好。
        assertNull(SunlightModeSettings.targetBrightness("1", 2048, 1, 16383))
        assertNull(SunlightModeSettings.targetBrightness("", 2048, 1, 16383))
    }

    @Test fun illegalRangesAreNeverWrittenTo() {
        // 量程非法（上限不大于下限）时一律返回 null —— 宁可这一档不生效，也不写。
        for (range in listOf(0 to 0, 5 to 5, 10 to 3, Int.MAX_VALUE to Int.MIN_VALUE)) {
            assertNull("$range", SunlightModeSettings.targetBrightness("2", 2048, range.first, range.second))
            assertNull("$range", SunlightModeSettings.targetBrightness("3", 2048, range.first, range.second))
            assertNull("$range", SunlightModeSettings.clampBrightness(2048, range.first, range.second))
        }
    }

    @Test fun storedBrightnessUsesTheDirectoryRangeNotTheDeviceRange() {
        assertEquals(SunlightModeSettings.BRIGHTNESS_DEFAULT, SunlightModeSettings.storedBrightness(""))
        assertEquals(SunlightModeSettings.BRIGHTNESS_DEFAULT, SunlightModeSettings.storedBrightness("abc"))
        assertEquals(SunlightModeSettings.BRIGHTNESS_MIN, SunlightModeSettings.storedBrightness("0"))
        assertEquals(SunlightModeSettings.BRIGHTNESS_MAX, SunlightModeSettings.storedBrightness("999999"))
        assertEquals(1234, SunlightModeSettings.storedBrightness("1234"))
    }
}
