/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.systemui

import org.junit.Assert.*
import org.junit.Test

class ClassicQsPolicyTest {
    @Test fun requestedMaximumDoesNotOverrideNativeMinimum() {
        assertEquals(2, ClassicQsPolicy.rowLimit(1, 3, 2))
        assertEquals(5, ClassicQsPolicy.rowLimit(5, 3, 1))
    }
    @Test fun disabledOrInvalidRequestUsesNativeCap() {
        assertEquals(3, ClassicQsPolicy.rowLimit(null, 3, 1))
        assertEquals(3, ClassicQsPolicy.rowLimit(0, 3, 1))
        assertEquals(3, ClassicQsPolicy.rowLimit(6, 3, 1))
    }
    @Test fun malformedNativeGeometryIsSkipped() {
        assertNull(ClassicQsPolicy.rowLimit(3, 0, 0))
        assertNull(ClassicQsPolicy.rowLimit(3, 3, -1))
        assertNull(ClassicQsPolicy.rowLimit(3, 3, 4))
    }
    @Test fun unchangedMaximumDoesNotForceRedistributionEveryMeasure() {
        assertFalse(ClassicQsPolicy.redistribute(null, 3, 3))
        assertTrue(ClassicQsPolicy.redistribute(null, 5, 3))
        assertFalse(ClassicQsPolicy.redistribute(5, 5, 3))
    }
    @Test fun turningOffOrChangingOrientationInvalidatesNativePageCache() {
        assertTrue(ClassicQsPolicy.redistribute(5, 3, 3))
        assertTrue(ClassicQsPolicy.redistribute(3, 2, 2))
        assertFalse(ClassicQsPolicy.redistribute(2, 2, 2))
    }
}
