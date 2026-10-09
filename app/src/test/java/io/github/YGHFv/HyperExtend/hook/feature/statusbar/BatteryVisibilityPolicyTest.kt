/* Copyright (C) 2026 YGHFv
 * SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import android.content.SharedPreferences
import io.github.YGHFv.HyperExtend.hook.HookSettings
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class BatteryVisibilityPolicyTest {
    @Test fun hidingIconCoversSolidAndHollowViewsInEveryStyle() {
        val policy = BatteryVisibilityPolicy(hideIcon = true)
        for (style in listOf(null, -1, 0, 1, 2, 3, 4)) {
            val expected = mutableListOf("mBatteryIconView", "mHollowBatteryIconView")
            if (style == 1) expected.add("mBatteryDigitalView")
            assertEquals("style=$style", expected, policy.hiddenViewFields(style))
            assertTrue(policy.hiddenTextFields().isEmpty())
        }
    }

    @Test fun chargingAndIconHidingAreIndependentAndNeverHideThePercentContainer() {
        for (icon in listOf(false, true)) for (charging in listOf(false, true)) {
            val policy = BatteryVisibilityPolicy(hideIcon = icon, hideCharging = charging)
            val fields = policy.hiddenViewFields(1)
            assertEquals(icon, "mBatteryIconView" in fields)
            assertEquals(icon, "mHollowBatteryIconView" in fields)
            assertEquals(charging, "mBatteryChargingView" in fields)
            assertFalse("mBatteryPercentContainer" in fields)
            assertEquals(icon || charging, policy.hidesAnything)
        }
    }

    @Test fun hidingPercentIncludesMarkAndLegacyDigitButMarkAloneKeepsNumbers() {
        for (percent in listOf(false, true)) for (mark in listOf(false, true)) {
            val policy = BatteryVisibilityPolicy(hidePercent = percent, hideMark = mark)
            val fields = policy.hiddenTextFields()
            assertEquals(percent, "mBatteryPercentView" in fields)
            assertEquals(percent, "mBatteryTextDigitView" in fields)
            assertEquals(percent || mark, "mBatteryPercentMarkView" in fields)
            assertTrue(policy.hiddenViewFields(1).isEmpty())
        }
    }

    @Test fun hiddenTextStaysZeroAcrossTheEntireFontSliderRange() {
        val percent = BatteryVisibilityPolicy(hidePercent = true)
        val mark = BatteryVisibilityPolicy(hideMark = true)
        for (stored in 0..200) {
            assertEquals(0f, percent.percentSizeDp(stored)!!, 0f)
            assertEquals(0f, percent.markSizeDp(stored)!!, 0f)
            assertEquals(0f, mark.markSizeDp(stored)!!, 0f)
            assertEquals(BatteryVisibilityPolicy().percentSizeDp(stored), mark.percentSizeDp(stored))
        }
    }

    @Test fun visibleTextRetainsHalfUnitConversionAndNativeSizeThreshold() {
        val policy = BatteryVisibilityPolicy()
        for (stored in 0..15) {
            assertNull(policy.percentSizeDp(stored))
            assertNull(policy.markSizeDp(stored))
        }
        for (stored in 16..200) {
            assertEquals(stored * 0.5f, policy.percentSizeDp(stored)!!, 0f)
            assertEquals(stored * 0.5f, policy.markSizeDp(stored)!!, 0f)
        }
    }

    @Test fun bothAfterHookOrdersSurviveRepeatedNativeFontResets() {
        // A text-state stub exercises policy composition, not Android rendering or Xposed dispatch.
        for (percent in listOf(false, true)) for (mark in listOf(false, true)) {
            for (custom in listOf(false, true)) for (styleLast in listOf(false, true)) {
                for (stored in listOf(0, 15, 16, 32, 200)) {
                    val policy = BatteryVisibilityPolicy(hidePercent = percent, hideMark = mark)
                    val text = mutableMapOf<String, Float>()
                    val hide = { policy.hiddenTextFields().forEach { text[it] = 0f } }
                    val style = {
                        if (custom) {
                            policy.percentSizeDp(stored)?.let { text["mBatteryPercentView"] = it }
                            policy.markSizeDp(stored)?.let { text["mBatteryPercentMarkView"] = it }
                        }
                    }
                    repeat(4) {
                        text["mBatteryPercentView"] = 13f
                        text["mBatteryPercentMarkView"] = 11f
                        text["mBatteryTextDigitView"] = 12f
                        if (styleLast) { hide(); style() } else { style(); hide() }
                        val customSize = (stored * 0.5f).takeIf { custom && it > 7.5f }
                        assertEquals(if (percent) 0f else customSize ?: 13f, text["mBatteryPercentView"]!!, 0f)
                        assertEquals(if (percent || mark) 0f else customSize ?: 11f, text["mBatteryPercentMarkView"]!!, 0f)
                        assertEquals(if (percent) 0f else 12f, text["mBatteryTextDigitView"]!!, 0f)
                    }
                }
            }
        }
    }

    @Test fun iconParentMustBeOnEvenWhenBatteryCustomizationIsEnabled() {
        val children = mapOf(
            "status_bar_icons.battery_icon" to true,
            "status_bar_icons.battery_percent" to true,
            "status_bar_icons.battery_percent_mark" to true,
            "status_bar_icons.battery_charging" to true,
            "status_bar_battery_style" to true,
            "status_bar_battery_style.custom" to true,
        )
        for (parent in listOf(false, true)) {
            val policy = BatteryVisibilityPolicy.from(settings(children + ("status_bar_icons" to parent)))
            assertEquals(parent, policy.hideIcon)
            assertEquals(parent, policy.hidePercent)
            assertEquals(parent, policy.hideMark)
            assertEquals(parent, policy.hideCharging)
        }
    }

    @Test fun missingOrDefaultPreferencesNeverActivateHiding() {
        assertEquals(BatteryVisibilityPolicy(), BatteryVisibilityPolicy.from(HookSettings(null)))
        assertEquals(BatteryVisibilityPolicy(), BatteryVisibilityPolicy.from(settings(emptyMap())))
        assertEquals(BatteryVisibilityPolicy(), BatteryVisibilityPolicy.from(settings(mapOf("status_bar_icons" to true))))
    }

    private fun settings(values: Map<String, Boolean>): HookSettings = HookSettings(
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            check(method.name == "getBoolean")
            values[args!![0]] ?: args[1]
        } as SharedPreferences,
    )
}
