/* Copyright (C) 2026 YGHFv; SPDX-License-Identifier: AGPL-3.0-or-later */
package io.github.YGHFv.HyperExtend.hook.feature.volume

import org.junit.Assert.*
import org.junit.Test

class AppVolumeExpandedLayoutTest {
    @Test fun mediaAlwaysReservesOneNativeAnimationSlot() {
        assertEquals(1, AppVolumeExpandedLayout.appCapacity(1, 3))
        assertEquals(2, AppVolumeExpandedLayout.appCapacity(8, 3))
        assertEquals(3, AppVolumeExpandedLayout.appCapacity(8, 4))
        assertEquals(4, AppVolumeExpandedLayout.appCapacity(32, 8))
    }

    @Test fun missingMediaOrAppsCannotCreateAnEmptyPanel() {
        assertEquals(0, AppVolumeExpandedLayout.appCapacity(0, 3))
        assertEquals(0, AppVolumeExpandedLayout.appCapacity(8, 1))
        assertEquals(0, AppVolumeExpandedLayout.appCapacity(8, 0))
    }

    @Test fun oneAppUsesOnlyMediaAndAppWidths() {
        assertEquals(136, AppVolumeExpandedLayout.contentWidth(listOf(64, 64), 8, 0))
        assertEquals(124, AppVolumeExpandedLayout.contentWidth(listOf(58, 58), 8, 0))
    }

    @Test fun nativeSpacingAndTrailingInsetAreKeptWithoutPhantomColumns() {
        assertEquals(210, AppVolumeExpandedLayout.contentWidth(listOf(64, 64, 64), 8, 2))
    }

    @Test fun partialLastPageOverlapsInsteadOfLeavingEmptySliders() {
        assertEquals(0, AppVolumeExpandedLayout.pageStart(0, 5, 3))
        assertEquals(2, AppVolumeExpandedLayout.pageStart(1, 5, 3))
        assertEquals(3, AppVolumeExpandedLayout.pageStart(1, 6, 3))
    }

    @Test fun everyAppIsReachableAndEveryPageIsFull() {
        for (count in 1..32) for (native in 2..5) {
            val capacity = AppVolumeExpandedLayout.appCapacity(count, native)
            val seen = mutableSetOf<Int>()
            repeat(AppVolumeBridgePolicy.pageCount(count, capacity)) { page ->
                val start = AppVolumeExpandedLayout.pageStart(page, count, capacity)
                val indices = start until start + capacity
                assertTrue(indices.all { it in 0 until count })
                seen.addAll(indices)
            }
            assertEquals((0 until count).toSet(), seen)
        }
    }
}
