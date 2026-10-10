/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class LockscreenChargingPolicyTest {
    private val sample = ChargingSample(-2_500_000, 4000, 325, 1, 2, 2, 1000)
    private fun details(value: ChargingSample? = sample, now: Long = 1000, ma: Boolean = false,
        temp: Boolean = false, locale: Locale = Locale.US) =
        LockscreenChargingPolicy.details(value, now, 3000, ma, temp, locale)

    @Test fun batteryUnitsProduceBatterySidePowerNotRatedChargerWattage() {
        assertEquals("2.5 A \u00b7 4.00 V \u00b7 ~10.00 W", details())
        assertEquals("2500 mA \u00b7 4.00 V \u00b7 ~10.00 W", details(ma = true))
    }
    @Test fun vendorCurrentSignDoesNotProduceNegativeChargingPower() {
        assertEquals(details(), details(sample.copy(currentMicroamps = 2_500_000)))
    }
    @Test fun missingAndUnsupportedCurrentAreNotInventedAsZero() {
        for (current in listOf(null, Int.MIN_VALUE, Int.MAX_VALUE, 0, 30_000_001))
            assertNull(details(sample.copy(currentMicroamps = current)))
    }
    @Test fun invalidVoltageRejectsCurrentAndPowerTogether() {
        for (voltage in listOf(null, -1, 0, 1999, 20001, Int.MAX_VALUE))
            assertNull(details(sample.copy(voltageMillivolts = voltage)))
    }
    @Test fun optionalTemperatureIsIndependentAndKeepsTenths() {
        assertEquals("32.5 \u00b0C", details(sample.copy(currentMicroamps = null), temp = true))
        assertTrue(details(temp = true)!!.endsWith("32.5 \u00b0C"))
        assertTrue(details(sample.copy(temperatureTenths = -15), temp = true)!!.endsWith("-1.5 \u00b0C"))
    }
    @Test fun unknownTemperatureIsOmittedInsteadOfZeroDegrees() {
        for (temperature in listOf(null, Int.MIN_VALUE, -501, 1001))
            assertEquals(details(), details(sample.copy(temperatureTenths = temperature), temp = true))
    }
    @Test fun supportedPlugAndChargingStatesOnly() {
        for (plug in listOf(1, 2, 4, 8)) for (status in listOf(2, 5))
            assertNotNull(details(sample.copy(plugged = plug, status = status)))
        for (plug in listOf(0, -1, 3, 16)) assertNull(details(sample.copy(plugged = plug)))
        for (status in listOf(0, 1, 3, 4)) assertNull(details(sample.copy(status = status)))
    }
    @Test fun batteryFaultAndUnknownHealthDoNotAppendMetrics() {
        for (health in listOf(0, 1, 3, 4, 5, 6, 7)) assertNull(details(sample.copy(health = health)))
    }
    @Test fun staleAndClockReversedSamplesNeverAppear() {
        assertNotNull(details(now = 6000))
        assertNull(details(now = 6001)); assertNull(details(now = 999)); assertNull(details(null))
    }
    @Test fun localeFormattingDoesNotAssumeDecimalPoint() {
        assertTrue(details(locale = Locale.GERMANY)!!.contains("2,5 A"))
    }
    @Test fun fullBatteryWithZeroCurrentDoesNotInventZeroWatts() {
        assertNull(details(sample.copy(currentMicroamps = 0, status = 5)))
        assertEquals("32.5 \u00b0C", details(sample.copy(currentMicroamps = 0, status = 5), temp = true))
    }
    private fun eligible(version: Long = 202602260, attached: Boolean = true, shown: Boolean = true,
        visible: Boolean = true, dozing: Boolean = false, blocked: Boolean = false, tiny: Boolean = false,
        plugged: Boolean = true, defender: Boolean = false, reverse: Int = 0, reposition: Boolean = false,
        type: Int = 3, match: Boolean = true) = LockscreenChargingPolicy.eligible(version, attached, shown,
            visible, dozing, blocked, tiny, plugged, defender, reverse, reposition, type, match)
    @Test fun onlyAuditedAttachedVisibleChargingIndicationIsEligible() {
        assertTrue(eligible())
        assertFalse(eligible(version = 202602261)); assertFalse(eligible(attached = false))
        assertFalse(eligible(shown = false)); assertFalse(eligible(visible = false))
        assertFalse(eligible(plugged = false)); assertFalse(eligible(match = false))
    }
    @Test fun aodTinyScreenProtectionAndSafetyStayNative() {
        assertFalse(eligible(dozing = true)); assertFalse(eligible(tiny = true)); assertFalse(eligible(blocked = true))
        assertFalse(eligible(defender = true)); assertFalse(eligible(reposition = true))
        assertFalse(eligible(reverse = 1)); assertFalse(eligible(reverse = -1))
    }
    @Test fun otherRotatingMessagesNeverAcquireChargingDetails() {
        for (type in -1..30) if (type != 3) assertFalse(eligible(type = type))
    }
    @Test fun existingUnlockHintFilterDoesNotRemoveBatteryOrAuthenticationErrors() {
        assertFalse(LockScreenHintPolicy.hide(3, "battery"))
        assertFalse(LockScreenHintPolicy.hide(11, "biometric_message"))
        assertFalse(LockScreenHintPolicy.hide(1, "disclosure"))
    }
    @Test fun requestsAreSingleFlightAndRespectFractionalInterval() {
        val state = ChargingRequestState()
        val first = state.begin(1000)!!
        assertNull(state.begin(1100))
        assertTrue(state.complete(first, 1200, 1500))
        assertNull(state.begin(2699)); assertNotNull(state.begin(2700))
    }
    @Test fun detachRebindAndHiddenSessionRejectOldResults() {
        val state = ChargingRequestState(); val old = state.begin(1000)!!
        state.cancel(); val next = state.begin(1100)!!
        assertFalse(state.complete(old, 1200, 3000))
        assertNull(state.begin(1300))
        assertTrue(state.complete(next, 1400, 3000))
    }
    @Test fun duplicateCompletionCannotReschedulePolling() {
        val state = ChargingRequestState(); val token = state.begin(1000)!!
        assertTrue(state.complete(token, 1000, 3000))
        assertFalse(state.complete(token, 5000, 3000))
        assertNotNull(state.begin(4000))
    }
}
