/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class NavigationHandleSettingsTest {
    @Test fun featureIsOptInAndSharesNavigationPanel() {
        val feature = featureById(NavigationHandleSettings.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(listOf("systemui"), feature.scopes)
        assertEquals(ScopeFeatureGroup.SYSTEM_UI_OTHER, feature.group)
        assertEquals(FeaturePanel.NAVIGATION, feature.panel)
        assertTrue(feature.hasDetailPage)
        assertTrue(feature.requirement!!.contains("四选一"))
        assertTrue(CONFIG_KEYS.containsAll(feature.config.map { it.key }))
        // 处理方式 + 半径 + 2 颜色 + 3 尺寸 + 4 导航栏几何 = 11
        assertEquals(11, feature.config.size)
        // 只剩隐藏方式的两个子项：原生隐藏档改用「按快照恢复」，不再有自己的从属开关。
        assertEquals(listOf(NavigationHandleSettings.SKIP_DRAW, NavigationHandleSettings.REPORT_HIDDEN),
            feature.options.map { it.id })
    }

    @Test fun processingModeDefaultsToAppearanceAndRejectsUnknownValues() {
        assertEquals(NavigationHandleSettings.MODE_APPEARANCE, NavigationHandleSettings.modeOf(""))
        assertEquals(NavigationHandleSettings.MODE_APPEARANCE, NavigationHandleSettings.modeOf("bad"))
        assertEquals(NavigationHandleSettings.MODE_HIDE, NavigationHandleSettings.modeOf("2"))
        assertEquals(NavigationHandleSettings.MODE_GEOMETRY, NavigationHandleSettings.modeOf("3"))
        assertEquals(NavigationHandleSettings.MODE_NATIVE, NavigationHandleSettings.modeOf("4"))
        assertEquals(NavigationHandleSettings.MODE_APPEARANCE, NavigationHandleSettings.mode.default)
    }

    @Test fun mergedCardsKeepTheirOriginalSettingKeys() {
        // 合并只搬主开关，子项键名保持不变，老用户的值继续生效。
        assertEquals("gesture_line", NavigationHandleSettings.LEGACY_HIDE_FEATURE)
        assertEquals("gesture_line.skip_draw", NavigationHandleSettings.SKIP_DRAW)
        assertEquals("gesture_line.report_hidden", NavigationHandleSettings.REPORT_HIDDEN)
        assertEquals("navigation_geometry", NavigationHandleSettings.LEGACY_GEOMETRY_FEATURE)
        assertEquals("navigation_geometry.height", NavigationGeometrySettings.barPortrait.key)
        assertEquals("navigation_geometry.frame_land", NavigationGeometrySettings.frameLandscape.key)
        assertEquals("systemui_hide_navigation_bar", NavigationHandleSettings.LEGACY_NATIVE_HIDE_FEATURE)
        assertEquals("hide_gesture_line", NavigationHandleSettings.NATIVE_GLOBAL_KEY)
        assertEquals("systemui_hide_navigation_bar.restore_on_disable", NavigationHandleSettings.LEGACY_NATIVE_RESTORE)
        assertFalse(FEATURES.any {
            it.id == "gesture_line" || it.id == "navigation_geometry" || it.id == "systemui_hide_navigation_bar"
        })
    }

    @Test fun nativeHideModeWritesTheSystemPreferenceNotAModuleKey() {
        // 原生隐藏档写的是系统自带全局键，且取值语义 0=显示 / 1=隐藏。
        assertNotEquals(NavigationHandleSettings.NATIVE_GLOBAL_KEY, NavigationHandleSettings.FEATURE)
        assertEquals(1, NavigationHandleSettings.nativeValueFor(true))
        assertEquals(0, NavigationHandleSettings.nativeValueFor(false))
        assertTrue(CONFIG_KEYS.none { it == NavigationHandleSettings.NATIVE_GLOBAL_KEY })
    }

    @Test fun nativeReconcileSavesTheOriginalValueExactlyOnce() {
        // 开启且没有快照：记下原值再写 1。
        val fresh = NavigationHandleSettings.nativeReconcilePlan(true, NavigationHandleSettings.NATIVE_SHOW, null)
        assertEquals(NavigationHandleSettings.NATIVE_HIDE, fresh.value)
        assertTrue(fresh.write)
        assertEquals(NavigationHandleSettings.NATIVE_SHOW, fresh.storeSnapshot)
        assertFalse(fresh.clearSnapshot)
        // 已经有快照：只写 1，绝不用「已经是我们写的 1」覆盖掉真正的原值。
        val kept = NavigationHandleSettings.nativeReconcilePlan(true, NavigationHandleSettings.NATIVE_HIDE,
            NavigationHandleSettings.NATIVE_SHOW)
        assertEquals(NavigationHandleSettings.NATIVE_HIDE, kept.value)
        assertTrue(kept.write)
        assertNull(kept.storeSnapshot)
        assertFalse(kept.clearSnapshot)
        // 当前值已经是我们写的 1 且没有快照：不能把 1 当原值存下来（否则恢复成了空操作）。
        val already = NavigationHandleSettings.nativeReconcilePlan(true, NavigationHandleSettings.NATIVE_HIDE, null)
        assertTrue(already.write)
        assertNull(already.storeSnapshot)
    }

    @Test fun nativeReconcileRestoresOnlyWhenWeLeftASnapshot() {
        // 关着且没快照 = 我们没改过 = 不需要复位，也不该写任何值。
        val untouched = NavigationHandleSettings.nativeReconcilePlan(false, NavigationHandleSettings.NATIVE_SHOW, null)
        assertFalse(untouched.write)
        assertNull(untouched.storeSnapshot)
        assertFalse(untouched.clearSnapshot)
        // 关着但有快照：写回原值并清掉快照。
        val restored = NavigationHandleSettings.nativeReconcilePlan(false, NavigationHandleSettings.NATIVE_HIDE, 0)
        assertTrue(restored.write)
        assertEquals(NavigationHandleSettings.NATIVE_SHOW, restored.value)
        assertNull(restored.storeSnapshot)
        assertTrue(restored.clearSnapshot)
    }

    @Test fun nativeHideMergeOnlyAddsAndNeverRemovesAnotherMode() {
        // 老卡片为真且用户没选过方式 → 打开新卡片并选原生隐藏。
        val fresh = MergedCardMigration.nativeHideMergePlan(true, false, null)!!
        assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), fresh.switches)
        assertEquals(mapOf(NavigationHandleSettings.MODE to NavigationHandleSettings.MODE_NATIVE), fresh.configs)
        // 用户已经显式选过别的处理方式 → 不覆盖他的选择。
        val chosen = MergedCardMigration.nativeHideMergePlan(true, null, NavigationHandleSettings.MODE_APPEARANCE)!!
        assertEquals(mapOf(NavigationHandleSettings.FEATURE to true), chosen.switches)
        assertTrue(chosen.configs.isEmpty())
        // 只出现过「关闭时复位」子项（老卡片主开关没写过）→ 打开卡片并按原生隐藏处理。
        val onlyRestore = MergedCardMigration.nativeHideMergePlan(null, true, null)!!
        assertTrue(onlyRestore.switches.isEmpty())
        // 老卡片为假 → 不写任何主开关，避免关掉用户正在用的别的处理方式。
        val off = MergedCardMigration.nativeHideMergePlan(false, null, null)!!
        assertTrue(off.switches.isEmpty()); assertTrue(off.configs.isEmpty())
        // 什么都没出现过 → 无事可做。
        assertNull(MergedCardMigration.nativeHideMergePlan(null, null, "1"))
    }

    @Test fun radiusRetainsHundredthDpAndHalfTenthStep() {
        val slider = NavigationHandleSettings.radius
        assertEquals(5, slider.step)
        assertEquals("1.85 dp", slider.display(slider.default))
        assertEquals("1.90 dp", slider.display(190))
        assertEquals("0.00 dp", slider.display(0))
        assertEquals(1.85f, NavigationHandleSettings.radiusDp(""), 0f)
        assertEquals(1.9f, NavigationHandleSettings.radiusDp("190"), 0f)
    }

    @Test fun invalidAndOutOfBoundsBackupValuesAreSafe() {
        assertEquals(1.85f, NavigationHandleSettings.radiusDp("NaN"), 0f)
        assertEquals(1.85f, NavigationHandleSettings.radiusDp("99999999999999"), 0f)
        assertEquals(0f, NavigationHandleSettings.radiusDp("-100"), 0f)
        assertEquals(5f, NavigationHandleSettings.radiusDp("800"), 0f)
    }

    @Test fun bothColorEndpointsSupportTransparencyAndBlankMeansNative() {
        val colors = NavigationHandleSettings.config.filterIsInstance<HyperColor>()
        assertEquals(2, colors.size)
        assertTrue(colors.all { it.allowAlpha })
        assertNull(SystemUiCustomSettings.color("", true))
        assertEquals(0xcc000000.toInt(), SystemUiCustomSettings.color("#CC000000", true))
        assertEquals(0xffffffff.toInt(), SystemUiCustomSettings.color("#ffffff", true))
        assertEquals(0, SystemUiCustomSettings.color("#00000000", true))
    }

    @Test fun alphaRoundTripDoesNotAlterExistingOpaqueMonetContract() {
        for (color in listOf(0, 0x00112233, 0xcc000000.toInt(), 0xffffffff.toInt())) {
            assertEquals(color, SystemUiCustomSettings.color(SystemUiCustomSettings.colorText(color, true), true))
        }
        assertNull(SystemUiCustomSettings.color("#CC000000"))
        assertEquals("#112233", SystemUiCustomSettings.colorText(0x80112233.toInt()))
        assertFalse((featureById("systemui_monet_custom")!!.config.single() as HyperColor).allowAlpha)
        for (bad in listOf("#GG112233", "#12345", "#123456789", "##CC000000", "#-C000000")) {
            assertNull(bad, SystemUiCustomSettings.color(bad, true))
        }
    }

    @Test fun existingSliderFormattingDoesNotChange() {
        assertEquals("0.5 dp", HyperSlider("test", "test", 0, 10, 1, divisor = 2, unit = " dp").display(1))
        assertEquals("8.0 dp", HyperSlider("test", "test", 0, 100, 80, divisor = 10, unit = " dp").display(80))
        assertEquals("85 %", HyperSlider("test", "test", 0, 100, 85, unit = " %").display(85))
    }

    @Test fun dimensionsAreRealOptionalInputsWithDistinctNativeSentinels() {
        for (raw in listOf("", "NaN", "-1", "41", "9999999999999")) {
            assertNull(NavigationHandleSettings.bottomDp(raw))
        }
        assertEquals(0f, NavigationHandleSettings.bottomDp("0")!!, 0f)
        assertEquals(40f, NavigationHandleSettings.bottomDp("40")!!, 0f)
        for (raw in listOf("", "0", "-1", "401")) assertNull(NavigationHandleSettings.widthDp(raw))
        assertEquals(200f, NavigationHandleSettings.widthDp("200")!!, 0f)
    }
}
