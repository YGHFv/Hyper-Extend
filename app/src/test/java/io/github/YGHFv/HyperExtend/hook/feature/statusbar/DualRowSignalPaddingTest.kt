/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.statusbar

import org.junit.Assert.assertEquals
import org.junit.Test

class DualRowSignalPaddingTest {
    @Test fun repeatedFramesDoNotAccumulateOffsets() {
        val edge = DualRowSignalPaddingEdge()
        var current = 8
        repeat(100) { current = edge.apply(current, -3) }
        assertEquals(5, current)
        assertEquals(8, edge.restore(current))
    }

    @Test fun hostUpdateBecomesBaselineInsteadOfBeingOverwritten() {
        val edge = DualRowSignalPaddingEdge()
        assertEquals(11, edge.apply(8, 3))
        assertEquals(15, edge.apply(12, 3))
        assertEquals(12, edge.restore(15))
    }

    @Test fun fallbackPreservesNewerNativePadding() {
        val edge = DualRowSignalPaddingEdge()
        edge.apply(8, 3)
        assertEquals(12, edge.restore(12))
        assertEquals(16, edge.restore(16))
    }

    @Test fun densityChangeReplacesOffsetInsteadOfAddingAgain() {
        val edge = DualRowSignalPaddingEdge()
        assertEquals(11, edge.apply(8, 3))
        assertEquals(14, edge.apply(11, 6))
        assertEquals(8, edge.restore(14))
    }

    @Test fun edgesAndReattachmentsHaveIndependentBaselines() {
        val left = DualRowSignalPaddingEdge()
        val right = DualRowSignalPaddingEdge()
        assertEquals(6, left.apply(8, -2))
        assertEquals(16, right.apply(12, 4))
        assertEquals(10, left.restore(10))
        assertEquals(12, right.restore(16))
        assertEquals(18, left.apply(20, -2))
        assertEquals(20, left.restore(18))
    }
}
