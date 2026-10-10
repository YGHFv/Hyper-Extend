/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class StockTilesPolicyTest {
    private val suffix = StockTilesPolicy.candidates.joinToString(",")

    @Test fun appendOnlyAddsTheSixAuditedNativeCandidates() {
        assertEquals("wifi,bt,$suffix", StockTilesPolicy.append("wifi,bt"))
        assertFalse("user" in StockTilesPolicy.candidates)
        assertFalse("dnd" in StockTilesPolicy.candidates)
        assertEquals(6, StockTilesPolicy.candidates.size)
    }

    @Test fun nativeOrderUnknownSpecsAndThirdPartyTilesAreUntouched() {
        val native = "satellite,screenrecordertile,milinkcast,custom(pkg/.Tile),wifi,new_vendor_tile"
        assertEquals("$native,$suffix", StockTilesPolicy.append(native))
    }

    @Test fun existingCandidatesAreNotDuplicated() {
        assertEquals("wifi,dark,saver,reduce_brightness,inversion,onehanded,color_correction",
            StockTilesPolicy.append("wifi,dark,saver"))
    }

    @Test fun repeatedQueriesAreIdempotent() {
        val once = StockTilesPolicy.append("wifi")
        assertEquals(once, StockTilesPolicy.append(once))
        assertSame(suffix, StockTilesPolicy.append(suffix))
    }

    @Test fun existingWhitespaceAndDuplicatesAreNotNormalized() {
        assertEquals("wifi,wifi, dark ,reduce_brightness,inversion,saver,onehanded,color_correction",
            StockTilesPolicy.append("wifi,wifi, dark "))
    }

    @Test fun trailingSeparatorDoesNotCreateAnExtraEmptySpec() {
        assertEquals("wifi,$suffix", StockTilesPolicy.append("wifi,"))
    }

    @Test fun missingOrUnreasonablyLargeNativeListFailsClosed() {
        for (value in listOf(null, "", "  ", "a".repeat(16385))) assertEquals(value, StockTilesPolicy.append(value))
    }

    @Test fun versionGatesRequireAuditedHosts() {
        assertTrue(StockTilesPolicy.supported(202602260))
        assertFalse(StockTilesPolicy.supported(null))
        assertFalse(StockTilesPolicy.supported(202602261))
        assertTrue(StockTilesPolicy.supported(202602260, 183022200, plugin = true))
        assertFalse(StockTilesPolicy.supported(202602260, null, plugin = true))
        assertFalse(StockTilesPolicy.supported(202602260, 183022201, plugin = true))
        assertFalse(StockTilesPolicy.supported(202602261, 183022200, plugin = true))
    }
}
