/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import io.github.YGHFv.HyperExtend.core.FeaturePanel
import io.github.YGHFv.HyperExtend.core.featureById
import io.github.YGHFv.HyperExtend.core.hasDetailPage
import io.github.YGHFv.HyperExtend.core.panel
import org.junit.Assert.*
import org.junit.Test

class TaplusTileTest {
    @Test fun tileIsOptInAndUsesItsOwnSpec() {
        val feature = featureById(TaplusTilePolicy.FEATURE)!!
        assertFalse(feature.defaultEnabled)
        assertEquals(FeaturePanel.TILES, feature.panel)
        assertNotEquals(GmsTilePolicy.SPEC, TaplusTilePolicy.SPEC)
        assertFalse(feature.hasDetailPage)
    }
    @Test fun editorCandidatesAreIdempotentAndKeepNativeOrder() {
        assertNull(TaplusTilePolicy.append(null))
        assertEquals("", TaplusTilePolicy.append(""))
        val list = "wifi,cell,${TaplusTilePolicy.SPEC}"
        assertEquals(list, TaplusTilePolicy.append("wifi,cell"))
        assertEquals(list, TaplusTilePolicy.append(list))
        assertEquals("wifi,${GmsTilePolicy.SPEC},${TaplusTilePolicy.SPEC}",
            TaplusTilePolicy.append("wifi,${GmsTilePolicy.SPEC}"))
    }
    @Test fun clickGatesRejectUnsafeOrStaleActions() {
        assertTrue(TaplusTilePolicy.canAct(true, false, true, true, true, 1500))
        assertFalse(TaplusTilePolicy.canAct(false, false, true, true, true, 0))
        assertFalse(TaplusTilePolicy.canAct(true, true, true, true, true, 0))
        assertFalse(TaplusTilePolicy.canAct(true, false, false, true, true, 0))
        assertFalse(TaplusTilePolicy.canAct(true, false, true, false, true, 0))
        assertFalse(TaplusTilePolicy.canAct(true, false, true, true, false, 0))
        assertFalse(TaplusTilePolicy.canAct(true, false, true, true, true, -1))
        assertFalse(TaplusTilePolicy.canAct(true, false, true, true, true, 1501))
    }
    private class Backend(var value: Boolean? = false) : NativeToggleTransaction.Backend {
        var allowed = true
        var writes = 0
        var afterRead: () -> Unit = {}
        var afterWrite: () -> Unit = {}
        override fun allowed() = allowed
        override fun read(): Boolean? = value.also { afterRead() }
        override fun write(enabled: Boolean) { writes++; value = enabled; afterWrite() }
    }
    @Test fun nativeArgumentsNeverUseNullToImplicitlyEnable() {
        assertEquals("open", TaplusTilePolicy.argument(true))
        assertEquals("close", TaplusTilePolicy.argument(false))
    }
    @Test fun transactionWritesOnlyAfterFreshComparison() {
        val backend = Backend()
        assertTrue(NativeToggleTransaction.toggle(false, backend))
        assertEquals(true, backend.value)
        assertEquals(1, backend.writes)
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(1, backend.writes)
    }
    @Test fun absentProviderStateDoesNotGuessAnOffValue() {
        val backend = Backend(null)
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(0, backend.writes)
    }
    @Test fun revocationDuringReadPreventsWriting() {
        val backend = Backend()
        backend.afterRead = { backend.allowed = false }
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(0, backend.writes)
    }
    @Test fun concurrentNativeChangeIsNotReportedAsSuccessfulOrOverwritten() {
        val backend = Backend()
        backend.afterWrite = { backend.value = false }
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(false, backend.value)
        assertEquals(1, backend.writes)
    }
    @Test fun disableDuringWriteDoesNotReportSuccessOrBlindlyRetry() {
        val backend = Backend()
        backend.afterWrite = { backend.allowed = false }
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(1, backend.writes)
    }
    @Test fun disableDuringFinalReadCannotReportSuccess() {
        val backend = Backend()
        var reads = 0
        backend.afterRead = { if (++reads == 2) backend.allowed = false }
        assertFalse(NativeToggleTransaction.toggle(false, backend))
        assertEquals(1, backend.writes)
    }
}
