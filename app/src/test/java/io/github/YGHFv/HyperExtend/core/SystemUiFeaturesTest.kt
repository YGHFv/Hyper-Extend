/*
 * Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.YGHFv.HyperExtend.core

import org.junit.Assert.*
import org.junit.Test

class SystemUiFeaturesTest {
    @Test fun sliderValueCardsAreMergedIntoThePercentageCard() {
        // 合并后不再有独立的两张「控制中心亮度值 / 音量值」卡片，
        // 它们是 slider_percentage 的原位数值方式（见 core/MergedCardMigration）。
        val percentage = featureById(PercentageSettings.FEATURE)!!
        assertFalse(percentage.defaultEnabled)
        assertEquals(FeaturePanel.NEW_CONTROL_CENTER, percentage.panel)
        assertEquals(listOf(PercentageSettings.BRIGHTNESS, PercentageSettings.VOLUME, PercentageSettings.BLUR),
            percentage.options.map { it.id })
        assertTrue(percentage.options.all { !it.defaultEnabled })
        assertTrue(percentage.requirement!!.contains("真机验收"))
        assertTrue(percentage.config.any { it.key == PercentageSettings.STYLE })
        for (legacy in listOf("control_center_brightness_value", "control_center_volume_value")) {
            assertNull("legacy card $legacy must be removed", featureById(legacy))
        }
        assertFalse(FEATURES.any { it.id == "control_center_brightness_value" })
        assertFalse(FEATURES.any { it.id == "control_center_volume_value" })
    }
    @Test fun volumeHoldIsAnOptInPartialMigrationWithNativeAccessibility() {
        val feature = featureById("volume_long_press_expand")!!
        assertFalse(feature.defaultEnabled); assertFalse(feature.hasDetailPage)
        assertEquals(FeaturePanel.VOLUME, feature.panel)
        assertTrue(feature.requirement!!.contains("有界部分迁移"))
        assertTrue(feature.requirement.contains("保留原生展开按钮"))
        assertTrue(feature.requirement.contains("触摸探索"))
    }

    @Test fun dimTileIconDoesNotAddTilesOrChangeBrightness() {
        val feature = featureById("control_center_dim_tile_icon")!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertTrue(feature.requirement!!.contains("不主动添加磁贴或开启极暗"))
        assertTrue(feature.requirement.contains("随原生状态刷新恢复"))
        assertTrue(feature.requirement.contains("无障碍语义"))
    }

    @Test fun stockTilesAreOptInCandidatesNotAutomaticLayoutChanges() {
        val feature = featureById("control_center_fix_tiles_list")!!
        assertFalse(feature.defaultEnabled)
        // 5G 样式是这张卡片的一行配置（样式一/二/内置详情页），不再是独立卡片。
        assertTrue(feature.hasDetailPage)
        assertEquals(listOf(FiveGTileSettings.STYLE), feature.config.map { it.key })
        assertEquals(3, FiveGTileSettings.style.entries.size)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.group)
        assertTrue(feature.requirement!!.contains("需手动添加"))
        assertTrue(feature.requirement.contains("不修改已保存布局和备份恢复"))
        assertTrue(feature.requirement.contains("关闭后不主动修改已保存布局"))
    }

    @Test fun autoFoldSwitchAlsoDocumentsMenuRemovalAndRecovery() {
        val feature = featureById("notification_disable_auto_fold")!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.summary.contains("收纳到更多通知"))
        assertTrue(feature.requirement!!.contains("仍可手动移出"))
    }

    @Test fun newFeaturesAreOptInAndRemainInSystemUi() {
        // 71 -> 67：两张滑块数值卡片并入 slider_percentage，
        // 「隐藏手势横条」「自定义导航栏几何」并入 navigation_handle_custom，
        // 「通知设置跳转到通知渠道」并入 notification_importance。
        // 67 -> 68：新增「隐藏底部手势横条」（OS4 原生 hide_gesture_line 适配）。
        // 68 -> 70：新增「静音模式」（上游雪豹模式的有界等价物）与「隐藏快捷开关标签」
        //        （上游只有开关没有实现，按 MiuiQSTileView 载体落地）；
        // 70 -> 69：把「隐藏底部手势横条」并入 navigation_handle_custom 的
        //        「改系统原生隐藏偏好」档，不再单独成卡（两张卡写的是同一个全局键）。
        // 69 -> 68：把「锁屏左侧入口替换为手电筒」并入锁屏左侧按钮卡片的
        //        「处理方式」三选一（上游本来就是一个下拉框，隐藏与手电筒抢同一个位置）。
        assertEquals(68, SYSTEM_UI_FEATURES.size)
        SYSTEM_UI_FEATURES.forEach { feature ->
            assertFalse(feature.defaultEnabled)
            assertEquals(when (feature.id) {
                NotificationManagementSettings.FEATURE -> listOf("systemui", "settings", "system_server")
                "control_center_unlock_old", "notification_importance" -> listOf("systemui", "settings")
                else -> listOf("systemui")
            }, feature.scopes)
            assertEquals(feature.config.isNotEmpty() || feature.options.isNotEmpty() || feature.extra != null, feature.hasDetailPage)
            assertNotNull(feature.entryGroup)
            assertTrue(feature.requirement!!.contains("重启系统界面"))
            assertTrue(feature.origin.contains("HyperCeiler"))
        }
    }

    @Test fun eachNewSubmenuContainsOnlyItsOwnFeatures() {
        for (group in listOf(ScopeFeatureGroup.LOCK_SCREEN, ScopeFeatureGroup.CONTROL_CENTER, ScopeFeatureGroup.SYSTEM_UI_OTHER)) {
            assertEquals(
                ((SYSTEM_UI_FEATURES + APP_VOLUME_FEATURE).filter { it.group == group }.map { it.id } +
                    if (group == ScopeFeatureGroup.SYSTEM_UI_OTHER) listOf("wallpaper_monet", "rotation_suggestion") else emptyList()).toSet(),
                featuresOfScopePage("systemui", group).map { it.id }.toSet(),
            )
        }
        val allKeys = FEATURES.flatMap { listOf(it.id) + it.options.map { option -> option.id } + it.config.map { row -> row.key } }
        assertEquals(allKeys.size, allKeys.toSet().size)
    }

    @Test fun lockscreenPrivacyImplicationsRemainVisible() {
        assertTrue(featureById("lockscreen_show_notifications")!!.requirement!!.contains("隐私"))
        assertTrue(featureById("lockscreen_keep_notifications")!!.requirement!!.contains("隐私"))
        assertTrue(featureById("lockscreen_hide_ble_toast")!!.requirement!!.contains("不改变蓝牙解锁条件"))
        assertTrue(featureById("lockscreen_third_party_biometrics")!!.requirement!!.contains("不伪造认证成功"))
        assertTrue(featureById("notification_zen_fix")!!.requirement!!.contains("优先通知例外"))
    }

    @Test fun existingLockscreenHooksAndChargingEntryArePresent() {
        assertEquals(
            setOf("lockscreen_show_notifications", "lockscreen_keep_notifications", "lockscreen_hide_status_bar",
                "lockscreen_hide_ble_toast", "lockscreen_scramble_pin", "lockscreen_double_tap", "lockscreen_hide_zen",
                "lockscreen_hide_hint", "lockscreen_third_party_biometrics", "lockscreen_charging_info",
                "lockscreen_hide_left_shortcut", "lockscreen_hide_right_shortcut",
                "lockscreen_wallpaper_transition", "lockscreen_shortcut_blur"),
            SYSTEM_UI_FEATURES.filter { it.group == ScopeFeatureGroup.LOCK_SCREEN }.map { it.id }.toSet(),
        )
    }

    @Test fun customSettingsAreIncludedInTheSharedSettingsCatalog() {
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_MONET_COLOR))
        assertTrue(CONFIG_KEYS.contains(SYSTEM_UI_FOCUS_PACKAGES))
        assertEquals(listOf(SYSTEM_UI_MONET_COLOR), featureById("systemui_monet_custom")!!.config.map { it.key })
        assertEquals(listOf(SYSTEM_UI_FOCUS_PACKAGES), featureById("notification_unlock_focus")!!.config.map { it.key })
        assertTrue(featureById("systemui_monet_custom")!!.config.single() is HyperColor)
        assertTrue(featureById("notification_unlock_focus")!!.config.single() is HyperAppSelection)
        assertTrue(featureById(NotificationExpansionSettings.EXPAND)!!.config.single() is HyperAppSelection)
    }

    @Test fun notificationImportanceNowOwnsTheMenuTargetChoice() {
        val feature = featureById(NotificationImportanceSettings.FEATURE)!!
        assertEquals("解锁通知重要程度", feature.title)
        // 并入原「通知设置跳转到通知渠道」卡片后，本卡片带上了菜单目标选择项。
        assertTrue(feature.hasDetailPage)
        assertEquals(listOf(NotificationImportanceSettings.MENU_TARGET), feature.config.map { it.key })
        assertNull(feature.extra)
        assertEquals(listOf("systemui", "settings"), feature.scopes)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertTrue(feature.requirement!!.contains("修复待真机验收"))
        assertTrue(feature.requirement.contains("重启系统界面"))
        assertFalse(feature.defaultEnabled)
        assertNull(featureById("notification_channel_settings"))
    }

    @Test fun notificationCountLimitOnlyControlsOverflowDismissal() {
        val feature = featureById("notification_remove_count_limit")!!
        assertFalse(feature.hasDetailPage)
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.requirement!!.contains("不绕过系统服务"))
        assertTrue(feature.requirement.contains("内存"))
    }

    @Test fun mediaDarkIsAnOptInNativeOnlySwitch() {
        val feature = featureById("media_card_always_dark")!!
        assertFalse(feature.hasDetailPage)
        assertFalse(feature.defaultEnabled)
        assertEquals(ScopeFeatureGroup.CONTROL_CENTER, feature.entryGroup)
        assertEquals(listOf("systemui"), feature.scopes)
        assertTrue(feature.requirement!!.contains("不移除锁屏/AOD 监听"))
        assertTrue(feature.requirement.contains("不含封面/渐变背景"))
    }
}
