/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.tsmclient

import android.os.Bundle
import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.TsmClientSettings
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TsmAutoNfcTest {
    private class FakeControl(
        var enabled: Boolean,
        var enableResult: Boolean = true,
        var disableResult: Boolean = true,
    ) : TsmAutoNfcControl {
        var enableCalls = 0
        var disableCalls = 0

        override fun isEnabled(): Boolean = enabled

        override fun enable(): Boolean {
            enableCalls++
            if (enableResult) enabled = true
            return enableResult
        }

        override fun disable(): Boolean {
            disableCalls++
            if (disableResult) enabled = false
            return disableResult
        }
    }

    private class TestActivity {
        @Suppress("unused")
        protected fun onCreate(state: Bundle?) = Unit

        @Suppress("unused")
        protected fun onDestroy() = Unit
    }

    @Test fun targetSignaturesStayExactAndNonStatic() {
        val methods = TestActivity::class.java.declaredMethods.associateBy { it.name }
        assertTrue(TsmAutoNfcTargets.matches(methods.getValue("onCreate"), TsmAutoNfcTargets.onCreate))
        assertTrue(TsmAutoNfcTargets.matches(methods.getValue("onDestroy"), TsmAutoNfcTargets.onDestroy))
        assertEquals(
            listOf("onCreate", "onDestroy"),
            TsmAutoNfcTargets.all.map { it.name },
        )
    }

    @Test fun nfcOriginallyOffIsEnabledAndRestoredOnClose() {
        val control = FakeControl(enabled = false)
        val policy = TsmAutoNfcPolicy()
        val owner = Any()

        assertTrue(policy.onOpened(owner, control))
        assertEquals(1, control.enableCalls)
        assertTrue(control.enabled)
        assertTrue(policy.onClosed(owner))
        assertEquals(1, control.disableCalls)
        assertFalse(control.enabled)
    }

    @Test fun nfcOriginallyOnIsNeverDisabled() {
        val control = FakeControl(enabled = true)
        val policy = TsmAutoNfcPolicy()
        val owner = Any()

        assertFalse(policy.onOpened(owner, control))
        assertFalse(policy.onClosed(owner))
        assertEquals(0, control.enableCalls)
        assertEquals(0, control.disableCalls)
        assertTrue(control.enabled)
    }

    @Test fun failedOrAlreadyHandledOpenDoesNotCreateOwnership() {
        val failed = FakeControl(enabled = false, enableResult = false)
        val policy = TsmAutoNfcPolicy()
        val owner = Any()
        assertFalse(policy.onOpened(owner, failed))
        assertFalse(policy.onClosed(owner))
        assertEquals(0, failed.disableCalls)

        val successful = FakeControl(enabled = false)
        assertTrue(policy.onOpened(owner, successful))
        assertFalse(policy.onOpened(owner, successful))
        assertEquals(1, successful.enableCalls)
    }

    @Test fun manuallyDisabledNfcIsNotDisabledTwice() {
        val control = FakeControl(enabled = false)
        val policy = TsmAutoNfcPolicy()
        val owner = Any()
        assertTrue(policy.onOpened(owner, control))
        control.enabled = false
        assertFalse(policy.onClosed(owner))
        assertEquals(0, control.disableCalls)
    }

    @Test fun featureRemainsAnOptInCardSwitch() {
        val feature = featureById(TsmClientSettings.AUTO_NFC)!!
        assertFalse(feature.defaultEnabled)
        assertFalse(feature.hasDetailPage)
        assertEquals(listOf("tsmclient"), feature.scopes)
        assertEquals(FeaturePanel.CARD, feature.panel)
    }
}
